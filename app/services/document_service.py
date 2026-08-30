"""Document access service.

The backend preserves official document URLs rather than duplicating government
files. The document endpoint returns metadata and the authoritative URL; a
redirect endpoint lets the Android app fetch through an API-controlled route
(useful for future access logging) without the server storing PDFs.
"""
from __future__ import annotations

from typing import List, Optional

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.database.models import Tender, TenderDocument


class DocumentService:
    def __init__(self, db: Session):
        self.db = db

    def list_for_tender(self, tender_id: int) -> List[TenderDocument]:
        return list(
            self.db.execute(
                select(TenderDocument).where(TenderDocument.tender_id == tender_id)
            ).scalars()
        )

    def get(self, document_id: int) -> Optional[TenderDocument]:
        return self.db.get(TenderDocument, document_id)
