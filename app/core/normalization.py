"""Normalization/mapping layer for categories and provinces.

Source data uses inconsistent naming; this module maps free-text source values
onto a stable internal taxonomy. Keyword matching is intentionally simple and
deterministic (no AI) and returns canonical slugs used throughout the system.
"""
from __future__ import annotations

import re
from typing import List, Optional

# ----------------------------------------------------------------------------
# Canonical internal categories: (slug, display name)
# ----------------------------------------------------------------------------
CATEGORIES: List[tuple[str, str]] = [
    ("construction", "Construction"),
    ("information-technology", "Information Technology"),
    ("supplies", "Supplies"),
    ("cleaning", "Cleaning"),
    ("security", "Security"),
    ("transport", "Transport"),
    ("professional-services", "Professional Services"),
    ("consulting", "Consulting"),
    ("medical", "Medical"),
    ("engineering", "Engineering"),
    ("electrical", "Electrical"),
    ("civil-works", "Civil Works"),
    ("agriculture", "Agriculture"),
    ("furniture", "Furniture"),
    ("vehicles", "Vehicles"),
    ("training", "Training"),
    ("other", "Other"),
]

CATEGORY_NAMES = {slug: name for slug, name in CATEGORIES}

# Ordered keyword -> slug rules. First match by priority wins for the primary
# category; all matches are returned for multi-category assignment.
_CATEGORY_KEYWORDS: List[tuple[str, List[str]]] = [
    ("information-technology", ["software", "ict", "information technology", " it ",
                                 "computer", "network", "hardware", "server", "laptop",
                                 "system development", "cloud", "cyber", "database",
                                 "website", "application development", "erp"]),
    ("electrical", ["electrical", "electrification", "substation", "transformer",
                     "power supply", "wiring", "lighting", "generator", "solar"]),
    ("civil-works", ["civil works", "road", "bridge", "storm water", "stormwater",
                      "sewer", "water reticulation", "pipeline", "pavement", "culvert"]),
    ("construction", ["construction", "building", "renovation", "refurbishment",
                       "upgrade of", "erection", "housing", "brick", "roofing",
                       "maintenance of building", "civil"]),
    ("engineering", ["engineering", "mechanical", "structural", "hvac",
                      "instrumentation", "fabrication"]),
    ("medical", ["medical", "health", "hospital", "clinic", "pharmaceutical",
                  "medicine", "surgical", "laboratory", "ppe", "ambulance",
                  "diagnostic"]),
    ("cleaning", ["cleaning", "hygiene", "sanitation", "pest control",
                   "waste removal", "refuse", "laundry"]),
    ("security", ["security", "guarding", "surveillance", "cctv", "access control",
                   "alarm"]),
    ("transport", ["transport", "logistics", "freight", "shuttle", "bus service",
                    "courier", "fleet", "haulage"]),
    ("vehicles", ["vehicle", "motor", "truck", "bakkie", "sedan", "trailer",
                   "automotive", "spare parts"]),
    ("furniture", ["furniture", "chairs", "desks", "office equipment", "shelving",
                    "cabinets"]),
    ("agriculture", ["agriculture", "farming", "livestock", "fertilizer", "seed",
                      "irrigation", "crop", "veterinary"]),
    ("training", ["training", "learnership", "skills development", "workshop",
                   "capacity building", "education", "bursary", "tuition"]),
    ("consulting", ["consulting", "consultancy", "advisory", "feasibility study",
                     "research", "audit", "assessment"]),
    ("professional-services", ["professional services", "legal", "accounting",
                                "financial services", "actuarial", "valuation",
                                "architectural", "quantity surveying", "panel of"]),
    ("cleaning", ["gardening", "landscaping"]),
    ("supplies", ["supply and delivery", "supply of", "procurement of", "goods",
                   "stationery", "materials", "equipment", "catering", "food"]),
]


def _norm_text(value: Optional[str]) -> str:
    if not value:
        return ""
    return f" {re.sub(r'[^a-z0-9 ]', ' ', value.lower())} "


def normalize_categories(*fields: Optional[str]) -> List[str]:
    """Return a de-duplicated, priority-ordered list of category slugs matched
    from the provided free-text fields (title, description, source category)."""
    haystack = " ".join(_norm_text(f) for f in fields)
    matched: List[str] = []
    for slug, keywords in _CATEGORY_KEYWORDS:
        if slug in matched:
            continue
        for kw in keywords:
            if kw in haystack:
                matched.append(slug)
                break
    if not matched:
        matched = ["other"]
    return matched


def primary_category(*fields: Optional[str]) -> str:
    return normalize_categories(*fields)[0]


# ----------------------------------------------------------------------------
# Provinces
# ----------------------------------------------------------------------------
PROVINCES: List[tuple[str, str]] = [
    ("eastern-cape", "Eastern Cape"),
    ("free-state", "Free State"),
    ("gauteng", "Gauteng"),
    ("kwazulu-natal", "KwaZulu-Natal"),
    ("limpopo", "Limpopo"),
    ("mpumalanga", "Mpumalanga"),
    ("northern-cape", "Northern Cape"),
    ("north-west", "North West"),
    ("western-cape", "Western Cape"),
    ("national", "National"),
]

PROVINCE_NAMES = {slug: name for slug, name in PROVINCES}

_PROVINCE_ALIASES = {
    "eastern-cape": ["eastern cape", "ec", "e cape", "e. cape"],
    "free-state": ["free state", "freestate", "fs"],
    "gauteng": ["gauteng", "gp", "gt", "johannesburg", "pretoria", "tshwane",
                 "ekurhuleni", "sandton", "midrand", "centurion", "soweto"],
    "kwazulu-natal": ["kwazulu-natal", "kwazulu natal", "kzn", "durban",
                       "pietermaritzburg", "ethekwini", "kwa-zulu", "kwazulu"],
    "limpopo": ["limpopo", "lp", "polokwane", "thohoyandou"],
    "mpumalanga": ["mpumalanga", "mp", "nelspruit", "mbombela", "witbank",
                    "emalahleni"],
    "northern-cape": ["northern cape", "nc", "kimberley", "upington"],
    "north-west": ["north west", "north-west", "nw", "mahikeng", "mafikeng",
                    "rustenburg", "potchefstroom"],
    "western-cape": ["western cape", "wc", "cape town", "stellenbosch",
                      "george", "paarl", "worcester"],
    "national": ["national", "republic of south africa", "rsa",
                  "south africa", "national department"],
}


def normalize_province(*fields: Optional[str]) -> Optional[str]:
    """Return a canonical province slug, or None if it can't be determined."""
    haystack = " ".join(_norm_text(f) for f in fields)
    # Prefer explicit province names (longer aliases) before city heuristics.
    best: Optional[str] = None
    best_len = 0
    for slug, aliases in _PROVINCE_ALIASES.items():
        for alias in aliases:
            token = f" {alias} "
            if token in haystack and len(alias) > best_len:
                best = slug
                best_len = len(alias)
    return best


# ----------------------------------------------------------------------------
# Municipalities (Sprint 8): canonical name -> province, with aliases.
# Used to recover a province (and a useful municipality field) when a source
# publishes no explicit region but names the municipality in its text.
# ----------------------------------------------------------------------------
MUNICIPALITIES: List[tuple[str, str, List[str]]] = [
    # (canonical, province slug, aliases)
    ("City of Johannesburg", "gauteng", ["city of johannesburg", "johannesburg"]),
    ("City of Tshwane", "gauteng", ["city of tshwane", "tshwane", "pretoria"]),
    ("Ekurhuleni", "gauteng", ["ekurhuleni"]),
    ("Emfuleni", "gauteng", ["emfuleni", "vereeniging", "vanderbijlpark"]),
    ("Merafong City", "gauteng", ["merafong", "carletonville"]),
    ("Mogale City", "gauteng", ["mogale city", "krugersdorp"]),
    ("City of Cape Town", "western-cape", ["city of cape town", "cape town", "kaapstad"]),
    ("Stellenbosch", "western-cape", ["stellenbosch"]),
    ("George", "western-cape", ["george municipality", "george local municipality"]),
    ("Drakenstein", "western-cape", ["drakenstein", "paarl"]),
    ("Buffalo City", "eastern-cape", ["buffalo city", "east london"]),
    ("Nelson Mandela Bay", "eastern-cape", ["nelson mandela bay", "gqeberha", "port elizabeth"]),
    ("Mnquma", "eastern-cape", ["mnquma", "butterworth"]),
    ("Mangaung", "free-state", ["mangaung", "bloemfontein"]),
    ("Matjhabeng", "free-state", ["matjhabeng", "welkom"]),
    ("Dihlabeng", "free-state", ["dihlabeng", "bethlehem"]),
    ("eThekwini", "kwazulu-natal", ["ethekwini", "durban"]),
    ("Msunduzi", "kwazulu-natal", ["msunduzi", "pietermaritzburg"]),
    ("uMhlathuze", "kwazulu-natal", ["umhlathuze", "richards bay"]),
    ("uMngeni", "kwazulu-natal", ["umngeni", "howick"]),
    ("Alfred Duma", "kwazulu-natal", ["alfred duma", "ladysmith"]),
    ("Newcastle", "kwazulu-natal", ["newcastle municipality", "newcastle local municipality"]),
    ("Ray Nkonyeni", "kwazulu-natal", ["ray nkonyeni", "port shepstone"]),
    ("Greater Kokstad", "kwazulu-natal", ["greater kokstad", "kokstad"]),
    ("Polokwane", "limpopo", ["polokwane"]),
    ("Thulamela", "limpopo", ["thulamela", "thohoyandou"]),
    ("Makhado", "limpopo", ["makhado", "louis trichardt"]),
    ("Mbombela", "mpumalanga", ["mbombela", "nelspruit"]),
    ("Emalahleni", "mpumalanga", ["emalahleni", "witbank"]),
    ("Govan Mbeki", "mpumalanga", ["govan mbeki", "secunda"]),
    ("Sol Plaatje", "northern-cape", ["sol plaatje", "kimberley"]),
    ("Dawid Kruiper", "northern-cape", ["dawid kruiper", "upington"]),
    ("Mahikeng", "north-west", ["mahikeng", "mafikeng"]),
    ("Rustenburg", "north-west", ["rustenburg"]),
    ("Potchefstroom", "north-west", ["potchefstroom", "tlokwe"]),
]


def detect_municipality(*fields: Optional[str]) -> Optional[tuple[str, str]]:
    """(canonical municipality name, province slug) if a known SA municipality
    is mentioned in the given text, else None. Longer aliases win so
    "Ray Nkonyeni" beats a bare city name."""
    haystack = " " + " ".join(_norm_text(f) for f in fields) + " "
    best: Optional[tuple[str, str]] = None
    best_len = 0
    for canonical, slug, aliases in MUNICIPALITIES:
        for alias in aliases:
            token = f" {alias} "
            if token in haystack and len(alias) > best_len:
                best = (canonical, slug)
                best_len = len(alias)
    return best


def province_name(slug: Optional[str]) -> Optional[str]:
    if not slug:
        return None
    return PROVINCE_NAMES.get(slug, slug)


def category_name(slug: Optional[str]) -> Optional[str]:
    if not slug:
        return None
    return CATEGORY_NAMES.get(slug, slug)
