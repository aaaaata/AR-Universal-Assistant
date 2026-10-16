from __future__ import annotations

import shutil
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from cloud.config import Settings
from cloud.errors import CloudError
from cloud.storage import SQLiteStore


@dataclass
class RetentionReport:
    status: str
    recovered_stale_analyses: int = 0
    expired_evidences: int = 0
    expired_session_recordings: int = 0
    recovered_stale_session_recordings: int = 0
    deleted_recording_temp_files: int = 0
    deleted_export_temp_files: int = 0
    evicted_automatic_evidences: int = 0
    deleted_debug_images: int = 0
    deleted_debug_image_bytes: int = 0
    deleted_orphan_images: int = 0
    deleted_analyses: int = 0
    vacuumed: bool = False
    capacity_protected: bool = False
    errors: list[str] = field(default_factory=list)

    def as_dict(self) -> dict[str, Any]:
        return {
            "status": self.status,
            "recovered_stale_analyses": self.recovered_stale_analyses,
            "expired_evidences": self.expired_evidences,
            "expired_session_recordings": self.expired_session_recordings,
            "recovered_stale_session_recordings": self.recovered_stale_session_recordings,
            "deleted_recording_temp_files": self.deleted_recording_temp_files,
            "deleted_export_temp_files": self.deleted_export_temp_files,
            "evicted_automatic_evidences": self.evicted_automatic_evidences,
            "deleted_debug_images": self.deleted_debug_images,
            "deleted_debug_image_bytes": self.deleted_debug_image_bytes,
            "deleted_orphan_images": self.deleted_orphan_images,
            "deleted_analyses": self.deleted_analyses,
            "vacuumed": self.vacuumed,
            "capacity_protected": self.capacity_protected,
            "errors": self.errors,
        }


class RetentionManager:
    """Best-effort storage maintenance kept outside request handling.

    The SQLite lease makes a timer in each Uvicorn worker safe: at most one
    process actually performs a sweep.  Failures are represented in the report
    so callers can log them without making an analysis request fail.
    """

    lease_name = "cloud_retention_cleanup"

    def __init__(self, settings: Settings, store: SQLiteStore, debug_image_dir: Path) -> None:
        self.settings = settings
        self.store = store
        self.debug_image_dir = debug_image_dir
        self.evidence_dir = settings.evidence_dir or (debug_image_dir.parent / "evidence")
        self.session_recording_dir = settings.session_recording_dir or (debug_image_dir.parent / "session_recordings")

    @staticmethod
    def _file_size(path: Path) -> int:
        try:
            return path.stat().st_size if path.is_file() else 0
        except OSError:
            return 0

    @classmethod
    def _directory_size(cls, directory: Path) -> int:
        if not directory.is_dir():
            return 0
        total = 0
        for child in directory.rglob("*"):
            total += cls._file_size(child)
        return total

    def sqlite_size_bytes(self) -> int:
        database = self.settings.database_path
        return sum(self._file_size(Path(f"{database}{suffix}")) for suffix in ("", "-wal", "-shm"))

    @staticmethod
    def _is_recording_export_temp(path: Path) -> bool:
        return path.is_file() and path.name.startswith("ar-mahjong-session-regression-") and path.suffix == ".zip"

    def storage_state(self) -> dict[str, int]:
        debug_bytes = self._directory_size(self.debug_image_dir)
        evidence_files = list(self.evidence_dir.rglob("*")) if self.evidence_dir.is_dir() else []
        evidence_bytes = sum(
            self._file_size(path) for path in evidence_files if not self._is_recording_export_temp(path)
        )
        export_bytes = sum(self._file_size(path) for path in evidence_files if self._is_recording_export_temp(path))
        export_dir = self.session_recording_dir.parent / "exports"
        if export_dir.resolve() != self.evidence_dir.resolve():
            export_bytes += self._directory_size(export_dir)
        recording_bytes = self._directory_size(self.session_recording_dir)
        sqlite_bytes = self.sqlite_size_bytes()
        ordinary_bytes = debug_bytes + evidence_bytes + export_bytes + sqlite_bytes
        actual_directory_bytes = debug_bytes + evidence_bytes + export_bytes + recording_bytes
        anchor = self.debug_image_dir if self.debug_image_dir.exists() else self.settings.database_path.parent
        disk = shutil.disk_usage(anchor)
        available_for_managed = ordinary_bytes + max(0, disk.free - self.settings.storage_min_free_bytes)
        effective_limit = min(self.settings.storage_max_bytes, available_for_managed)
        return {
            "debug_image_bytes": debug_bytes,
            "evidence_bytes": evidence_bytes,
            "evidence_directory_bytes": evidence_bytes,
            "session_recording_bytes": recording_bytes,
            "export_temp_bytes": export_bytes,
            "sqlite_bytes": sqlite_bytes,
            "ordinary_storage_bytes": ordinary_bytes,
            "managed_bytes": ordinary_bytes,
            "actual_directory_bytes": actual_directory_bytes,
            "disk_free_bytes": disk.free,
            "effective_storage_limit_bytes": effective_limit,
        }

    def can_store_debug_image(self, incoming_bytes: int = 0) -> tuple[bool, str | None]:
        if incoming_bytes < 0:
            raise ValueError("incoming_bytes must be non-negative")
        state = self.storage_state()
        if state["disk_free_bytes"] - incoming_bytes <= self.settings.storage_min_free_bytes:
            return False, "STORAGE_MIN_FREE_SPACE_REACHED"
        if state["debug_image_bytes"] + incoming_bytes > self.settings.debug_image_max_bytes:
            return False, "DEBUG_IMAGE_CAPACITY_REACHED"
        if state["managed_bytes"] + incoming_bytes > state["effective_storage_limit_bytes"]:
            return False, "STORAGE_CAPACITY_REACHED"
        return True, None

    def recording_session_dir(self, session_id: str) -> Path:
        candidate = self.session_recording_dir / session_id
        try:
            candidate.resolve().relative_to(self.session_recording_dir.resolve())
        except (OSError, ValueError) as exc:
            raise CloudError("INVALID_RECORDING_PATH", "Recording path is invalid", 500) from exc
        return candidate

    def prepare_for_session_recording(self, required_bytes: int) -> None:
        """Conservatively verify headroom without creating a reservation."""
        if required_bytes <= 0:
            raise ValueError("required_bytes must be positive")
        report = RetentionReport(status="recording_preflight")
        self._cleanup_expired_images(report)
        self._cleanup_capacity_images(report)
        self.session_recording_dir.mkdir(parents=True, exist_ok=True)
        while shutil.disk_usage(self.session_recording_dir).free < required_bytes + self.settings.storage_min_free_bytes:
            candidates = self.store.list_debug_assets(exclude_active_evidence=True, limit=1)
            if not candidates:
                raise CloudError(
                    "REGRESSION_RECORDING_STORAGE_UNAVAILABLE",
                    "Insufficient storage for a regression recording session",
                    507,
                )
            if not self._purge_asset(candidates[0], "REGRESSION_RECORDING_PRECHECK", report):
                raise CloudError(
                    "REGRESSION_RECORDING_STORAGE_UNAVAILABLE",
                    "Insufficient storage for a regression recording session",
                    507,
                )

    def cleanup_expired_session_recordings(self, report: RetentionReport) -> None:
        for recording in self.store.list_expired_session_recordings():
            session_id = str(recording["session_id"])
            directory = self.recording_session_dir(session_id)
            cleanup_holder_id = self.store.try_acquire_session_recording_cleanup(session_id)
            if cleanup_holder_id is None:
                continue
            try:
                if directory.exists():
                    shutil.rmtree(directory)
                self.store.expire_session_recording(session_id, cleanup_holder_id)
                report.expired_session_recordings += 1
            except (OSError, CloudError) as exc:
                report.errors.append(f"recording cleanup failed for {session_id}: {exc}")
            finally:
                self.store.release_session_recording_export(session_id, cleanup_holder_id)

    def _cleanup_orphan_recording_temps(self, report: RetentionReport) -> None:
        cutoff = time.time() - self.settings.session_recording_retention_days * 24 * 60 * 60
        if self.session_recording_dir.is_dir():
            for path in self.session_recording_dir.rglob("*.tmp"):
                try:
                    if path.is_file() and path.stat().st_mtime <= cutoff:
                        path.unlink()
                        report.deleted_recording_temp_files += 1
                except OSError as exc:
                    report.errors.append(f"recording temp cleanup failed for {path.name}: {exc}")
        export_roots = {self.evidence_dir, self.session_recording_dir.parent / "exports"}
        for root in export_roots:
            if not root.is_dir():
                continue
            for path in root.rglob("ar-mahjong-session-regression-*.zip"):
                try:
                    if path.is_file() and path.stat().st_mtime <= cutoff:
                        path.unlink()
                        report.deleted_export_temp_files += 1
                except OSError as exc:
                    report.errors.append(f"recording export cleanup failed for {path.name}: {exc}")

    def _asset_path(self, asset: dict[str, Any]) -> Path:
        value = asset.get("image_path")
        if value:
            return Path(str(value))
        return self.debug_image_dir / f"{asset['analysis_id']}.png"

    def _safe_debug_path(self, path: Path) -> Path | None:
        try:
            root = self.debug_image_dir.resolve()
            candidate = path.resolve()
        except OSError:
            return None
        try:
            candidate.relative_to(root)
        except ValueError:
            return None
        return candidate

    def _purge_asset(self, asset: dict[str, Any], reason: str, report: RetentionReport) -> bool:
        path = self._safe_debug_path(self._asset_path(asset))
        if path is not None:
            try:
                if path.is_file():
                    size = path.stat().st_size
                    path.unlink()
                    report.deleted_debug_images += 1
                    report.deleted_debug_image_bytes += size
            except OSError as exc:
                report.errors.append(f"debug image delete failed for {asset['analysis_id']}: {exc}")
                return False
        self.store.mark_debug_asset_purged(str(asset["analysis_id"]), reason)
        return True

    def _cleanup_missing_assets(self) -> int:
        changed = 0
        for asset in self.store.list_debug_assets(stored_only=True):
            path = self._safe_debug_path(self._asset_path(asset))
            if path is None or not path.is_file():
                self.store.mark_debug_asset_missing(str(asset["analysis_id"]))
                changed += 1
        return changed

    def _cleanup_orphan_images(self, report: RetentionReport) -> None:
        if not self.debug_image_dir.is_dir():
            return
        for image_path in self.debug_image_dir.glob("*.png"):
            analysis_id = image_path.stem
            asset = self.store.get_debug_asset(analysis_id)
            if asset and asset.get("status") == "stored":
                continue
            # Databases created before S4 have image files but no debug_assets
            # rows. Reconcile those files instead of treating valid historical
            # analysis images as orphans on the first maintenance pass.
            if asset is None and self.store.analysis_exists(analysis_id):
                try:
                    self.store.record_debug_asset(analysis_id, image_path, image_path.stat().st_size)
                    continue
                except (OSError, Exception) as exc:
                    report.errors.append(f"debug image metadata reconciliation failed for {image_path.name}: {exc}")
                    continue
            try:
                image_path.unlink()
                report.deleted_orphan_images += 1
            except OSError as exc:
                report.errors.append(f"orphan debug image delete failed for {image_path.name}: {exc}")

    def _needs_capacity_cleanup(self) -> bool:
        state = self.storage_state()
        return (
            state["debug_image_bytes"] > int(self.settings.debug_image_max_bytes * 0.8)
            or state["managed_bytes"] > int(state["effective_storage_limit_bytes"] * 0.8)
        )

    def _cleanup_capacity_images(self, report: RetentionReport) -> None:
        while self._needs_capacity_cleanup():
            candidates = self.store.list_debug_assets(exclude_active_evidence=True, limit=1)
            if not candidates:
                report.capacity_protected = True
                return
            if not self._purge_asset(candidates[0], "CAPACITY_EVICTED", report):
                report.capacity_protected = True
                return

    def _cleanup_expired_images(self, report: RetentionReport) -> None:
        for asset in self.store.list_debug_assets(
            older_than_days=self.settings.debug_image_retention_days,
            exclude_active_evidence=True,
        ):
            self._purge_asset(asset, "RETENTION_TTL_EXPIRED", report)

    def _evict_automatic_evidence_if_needed(self, report: RetentionReport) -> None:
        target = int(self.settings.evidence_max_bytes * 0.8)
        while self.store.active_evidence_asset_bytes() > target:
            evicted = self.store.evict_oldest_automatic_evidence(limit=1)
            if not evicted:
                report.capacity_protected = True
                return
            report.evicted_automatic_evidences += len(evicted)
        # Images released by automatic evidence eviction become normal capacity
        # candidates and are removed by the subsequent normal cleanup pass.

    def _delete_expired_analyses(self, report: RetentionReport, retention_days: int) -> None:
        for analysis in self.store.list_expired_analyses(retention_days):
            if analysis.get("debug_asset_status") == "stored":
                self._purge_asset(analysis, "ANALYSIS_RETENTION_EXPIRED", report)
            if self.store.delete_analysis_if_prunable(str(analysis["analysis_id"])):
                report.deleted_analyses += 1

    def _enforce_sqlite_limit(self, report: RetentionReport) -> None:
        target = int(self.settings.sqlite_max_bytes * 0.8)
        while self.sqlite_size_bytes() > target:
            candidates = self.store.list_expired_analyses(0, limit=1)
            if not candidates:
                report.capacity_protected = True
                break
            analysis = candidates[0]
            if analysis.get("debug_asset_status") == "stored":
                self._purge_asset(analysis, "SQLITE_CAPACITY_EVICTED", report)
            if self.store.delete_analysis_if_prunable(str(analysis["analysis_id"])):
                report.deleted_analyses += 1
            else:
                break
        if report.deleted_analyses and self.store.checkpoint_and_vacuum():
            report.vacuumed = True

    def run_once(self) -> RetentionReport:
        if not self.settings.retention_enabled:
            return RetentionReport(status="disabled")
        holder_id = str(uuid.uuid4())
        lease_seconds = max(60, self.settings.retention_cleanup_interval_minutes * 60)
        try:
            if not self.store.try_acquire_maintenance_lease(self.lease_name, holder_id, lease_seconds):
                return RetentionReport(status="lease_busy")
        except Exception as exc:  # pragma: no cover - defensive request-path isolation
            return RetentionReport(status="lease_failed", errors=[str(exc)])

        report = RetentionReport(status="completed")
        try:
            report.recovered_stale_analyses = self.store.recover_stale_in_flights(
                self.settings.pending_analysis_stale_minutes
            )
            report.expired_evidences = self.store.expire_evidence()
            report.recovered_stale_session_recordings = self.store.recover_stale_session_recordings(
                self.settings.session_recording_retention_days
            )
            self._cleanup_orphan_recording_temps(report)
            self.cleanup_expired_session_recordings(report)
            self._cleanup_missing_assets()
            self._cleanup_expired_images(report)
            self._evict_automatic_evidence_if_needed(report)
            self._cleanup_capacity_images(report)
            self._delete_expired_analyses(report, self.settings.analysis_retention_days)
            self._cleanup_orphan_images(report)
            self._enforce_sqlite_limit(report)
            if not report.vacuumed and self.sqlite_size_bytes() > self.settings.sqlite_max_bytes:
                report.capacity_protected = True
        except Exception as exc:  # Maintenance must never crash the service loop.
            report.status = "failed"
            report.errors.append(str(exc))
        finally:
            try:
                self.store.release_maintenance_lease(self.lease_name, holder_id)
            except Exception as exc:  # pragma: no cover - defensive only
                report.errors.append(f"maintenance lease release failed: {exc}")
        return report
