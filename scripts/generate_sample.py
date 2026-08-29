"""Generate a realistic OCDS-shaped sample dataset for development.

Writes scripts/sample_tenders.json as an OCDS release package. These records
are CLEARLY development data: the ingestion service marks them is_sample=True
and only loads them as a resilience fallback when the live source is
unreachable and the DB is empty. Never mixed into a populated/production DB.

Run:  python scripts/generate_sample.py
"""
from __future__ import annotations

import json
import os
import random
from datetime import datetime, timedelta, timezone

random.seed(42)
SAST = timezone(timedelta(hours=2))
NOW = datetime.now(SAST)

ORGS = [
    ("City of Johannesburg Metropolitan Municipality", "Gauteng", "ZA-GT-JHB"),
    ("eThekwini Municipality", "KwaZulu-Natal", "ZA-KZN-ETH"),
    ("City of Cape Town", "Western Cape", "ZA-WC-CPT"),
    ("Department of Health - Eastern Cape", "Eastern Cape", "ZA-EC-DOH"),
    ("Limpopo Department of Public Works", "Limpopo", "ZA-LP-DPW"),
    ("Mangaung Metropolitan Municipality", "Free State", "ZA-FS-MAN"),
    ("Department of Education - Mpumalanga", "Mpumalanga", "ZA-MP-DOE"),
    ("Sol Plaatje Municipality", "Northern Cape", "ZA-NC-SOL"),
    ("Rustenburg Local Municipality", "North West", "ZA-NW-RUS"),
    ("National Department of Transport", "National", "ZA-NAT-DOT"),
    ("City of Tshwane", "Gauteng", "ZA-GT-TSH"),
    ("uMgungundlovu District Municipality", "KwaZulu-Natal", "ZA-KZN-UMG"),
]

TITLES = [
    ("Supply and Delivery of Office Furniture", "goods",
     "Supply and delivery of ergonomic office chairs, desks and shelving to municipal offices."),
    ("Construction of Community Health Centre", "works",
     "Construction of a new 24-hour community health centre including civil works and electrical installation."),
    ("Provision of Cleaning and Hygiene Services", "services",
     "Appointment of a service provider for cleaning, hygiene and pest control services for a 24 month period."),
    ("Supply of ICT Hardware and Network Equipment", "goods",
     "Procurement of laptops, servers and network switches including software licensing and support."),
    ("Security Guarding Services for Municipal Buildings", "services",
     "Provision of physical security guarding and CCTV surveillance services across municipal facilities."),
    ("Upgrading of Internal Roads and Storm Water", "works",
     "Civil works for the upgrading of internal roads, storm water drainage and pavement rehabilitation."),
    ("Supply and Delivery of Medical Consumables", "goods",
     "Supply of surgical consumables, PPE and pharmaceutical items to district clinics."),
    ("Appointment of Engineering Consultants", "services",
     "Panel of professional engineering and quantity surveying consultants for infrastructure projects."),
    ("Provision of Learner Transport Services", "services",
     "Scholar transport services (bus and shuttle) for rural schools for the 2026 academic year."),
    ("Electrification of Rural Households", "works",
     "Electrical reticulation, transformer installation and household electrification programme."),
    ("Supply of Agricultural Equipment and Seed", "goods",
     "Supply and delivery of irrigation equipment, fertilizer and seed to emerging farmers."),
    ("Skills Development and Learnership Programme", "services",
     "Training and capacity building learnership programme for unemployed youth."),
    ("Supply and Delivery of Municipal Fleet Vehicles", "goods",
     "Procurement of light delivery vehicles, trucks and trailers including maintenance."),
    ("Refurbishment of Municipal Offices", "works",
     "Renovation and refurbishment of municipal head office building including HVAC."),
    ("Financial and Internal Audit Services", "services",
     "Appointment of a firm to provide internal audit, advisory and financial services."),
]

MPC = {"goods": "goods", "works": "works", "services": "services"}


def build_release(i: int) -> dict:
    org_name, province, org_id = random.choice(ORGS)
    title, cat, desc = random.choice(TITLES)
    advertised = NOW - timedelta(days=random.randint(0, 20))

    # Distribute closing dates: some past (expired/closed), most future.
    roll = random.random()
    if roll < 0.15:
        closing = NOW - timedelta(days=random.randint(9, 20))   # past retention -> will expire
    elif roll < 0.25:
        closing = NOW - timedelta(hours=random.randint(2, 40))  # just closed
    elif roll < 0.45:
        closing = NOW + timedelta(hours=random.randint(3, 47))  # closing soon
    else:
        closing = NOW + timedelta(days=random.randint(3, 30))   # active
    closing = closing.replace(hour=11, minute=0, second=0, microsecond=0)

    ext_id = f"SAMPLE-{org_id}-{2026}-{i:04d}"
    ocid = f"ocds-9nqbfw-{ext_id}"

    tender = {
        "id": ext_id,
        "title": f"{title} ({org_name.split()[0]})",
        "description": desc,
        "status": "active",
        "mainProcurementCategory": MPC[cat],
        "procurementMethod": "open",
        "procurementMethodDetails": "Open Tender",
        "submissionMethod": ["electronicSubmission"],
        "submissionMethodDetails": "Submit via the eTenders portal.",
        "datePublished": advertised.isoformat(),
        "tenderPeriod": {
            "startDate": advertised.isoformat(),
            "endDate": closing.isoformat(),
        },
        "procuringEntity": {"id": org_id, "name": org_name},
        "documents": [
            {
                "id": f"{ext_id}-DOC1",
                "documentType": "tenderNotice",
                "title": "Tender Notice",
                "url": f"https://www.etenders.gov.za/sample/{ext_id}/notice.pdf",
                "format": "application/pdf",
            },
            {
                "id": f"{ext_id}-DOC2",
                "documentType": "biddingDocuments",
                "title": "Bid Document",
                "url": f"https://www.etenders.gov.za/sample/{ext_id}/bid.pdf",
                "format": "application/pdf",
            },
        ],
    }

    return {
        "ocid": ocid,
        "id": f"{ocid}-01",
        "date": advertised.isoformat(),
        "tag": ["tender"],
        "initiationType": "tender",
        "language": "en",
        "buyer": {"id": org_id, "name": org_name},
        "parties": [
            {
                "id": org_id,
                "name": org_name,
                "roles": ["buyer", "procuringEntity"],
                "identifier": {"scheme": "ZA-CIPC", "id": org_id, "legalName": org_name},
                "address": {"region": province, "countryName": "South Africa"},
            }
        ],
        "tender": tender,
    }


def main() -> None:
    releases = [build_release(i) for i in range(1, 41)]
    package = {
        "uri": "development-sample",
        "version": "1.1",
        "publishedDate": NOW.isoformat(),
        "publisher": {"name": "DEVELOPMENT SAMPLE (not real tender data)"},
        "releases": releases,
    }
    out = os.path.join(os.path.dirname(__file__), "sample_tenders.json")
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(package, fh, indent=2)
    print(f"Wrote {len(releases)} sample releases to {out}")


if __name__ == "__main__":
    main()
