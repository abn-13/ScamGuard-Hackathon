"""Small bounded in-memory TTL cache for optional network enrichment."""

from copy import deepcopy
from threading import Lock
from time import monotonic
from typing import Generic, Hashable, Optional, TypeVar


T = TypeVar("T")


class TTLCache(Generic[T]):
    """Cache JSON-like values for a short time without adding infrastructure."""

    def __init__(self, ttl_seconds: int, max_entries: int = 256) -> None:
        self.ttl_seconds = ttl_seconds
        self.max_entries = max_entries
        self._items: dict[Hashable, tuple[float, T]] = {}
        self._lock = Lock()

    def get(self, key: Hashable) -> Optional[T]:
        now = monotonic()
        with self._lock:
            item = self._items.get(key)
            if item is None:
                return None
            expires_at, value = item
            if expires_at <= now:
                self._items.pop(key, None)
                return None
            return deepcopy(value)

    def set(self, key: Hashable, value: T) -> None:
        now = monotonic()
        with self._lock:
            expired = [key for key, (expires, _) in self._items.items() if expires <= now]
            for expired_key in expired:
                self._items.pop(expired_key, None)
            if len(self._items) >= self.max_entries:
                oldest_key = min(self._items, key=lambda item_key: self._items[item_key][0])
                self._items.pop(oldest_key, None)
            self._items[key] = (now + self.ttl_seconds, deepcopy(value))

    def clear(self) -> None:
        with self._lock:
            self._items.clear()
