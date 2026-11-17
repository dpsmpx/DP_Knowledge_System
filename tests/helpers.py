import tempfile
from pathlib import Path

from ksys.core.clock import FixedClock
from ksys.store.kb import KnowledgeBase
from ksys.store.workspace import Workspace


class TempKB:
    """Рабочее пространство во временной папке с детерминированными часами."""

    def __init__(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.ws = Workspace(Path(self.tmp.name) / "ws")
        self.ws.init()
        self.clock = FixedClock("2026-10-05T10:00:00Z")
        self.kb = KnowledgeBase(self.ws, self.clock)

    def reopen(self, at=None):
        return KnowledgeBase(self.ws, FixedClock("2030-01-01T00:00:00Z"), at=at)

    def close(self):
        self.tmp.cleanup()
