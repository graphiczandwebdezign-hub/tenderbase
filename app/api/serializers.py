"""Model -> response serialization helpers."""
from __future__ import annotations

from app.database.models import Tender
from app.schemas.tender import TenderOut, TenderDetailOut, DocumentOut, AmendmentOut
from app.services.tender_service import TenderService


def _documents(tender: Tender):
    return [
        DocumentOut(
            id=d.id,
            title=d.title,
            url=d.url,
            type=d.document_type,
            filename=d.filename,
            mime_type=d.mime_type,
            file_size=d.file_size,
        )
        for d in tender.documents
    ]


def _category_slugs(tender: Tender):
    return [link.category.slug for link in tender.categories if link.category]


def serialize_tender(tender: Tender) -> TenderOut:
    return TenderOut(
        id=tender.id,
        source=tender.source,
        tender_number=tender.tender_number,
        ocid=tender.ocid,
        title=tender.title,
        description=tender.description,
        organisation=tender.organisation,
        province=tender.province,
        municipality=tender.municipality,
        category=tender.category,
        categories=_category_slugs(tender),
        tender_type=tender.tender_type,
        status=tender.status.value,
        deadline_state=TenderService.deadline_state(tender),
        advertised_date=tender.advertised_date,
        closing_date=tender.closing_date,
        closing_time=tender.closing_time,
        closing_at=tender.closing_at,
        submission_method=tender.submission_method,
        source_url=tender.source_url,
        is_sample=tender.is_sample,
        documents=_documents(tender),
    )


def serialize_tender_detail(tender: Tender) -> TenderDetailOut:
    base = serialize_tender(tender).model_dump()
    base["amendments"] = [
        AmendmentOut(
            id=a.id,
            field_changed=a.field_changed,
            old_value=a.old_value,
            new_value=a.new_value,
            detected_at=a.detected_at,
        )
        for a in tender.amendments
    ]
    return TenderDetailOut(**base)
