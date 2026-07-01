import os
import sys
import logging
import threading

logger = logging.getLogger(__name__)

try:
    from faster_whisper import WhisperModel, BatchedInferencePipeline
except ImportError:
    logger.warning("WARNING: faster-whisper not found. STT will not work.")
    WhisperModel = None
    BatchedInferencePipeline = None

class STTService:
    def __init__(self, model="base", language="zh"):
        self.model_name = model
        self.language = language
        self._model = None
        self.batched_model = None
        self._init_lock = threading.Lock()
        self._init_attempted = False
        logger.info(
            "STT Service created in lazy mode (model='%s', language='%s').",
            model,
            language,
        )

    def _ensure_initialized(self):
        if self.batched_model is not None:
            return
        if self._init_attempted and self.batched_model is None:
            raise RuntimeError("STT Service not available (previous initialization failed).")

        with self._init_lock:
            if self.batched_model is not None:
                return
            if self._init_attempted and self.batched_model is None:
                raise RuntimeError("STT Service not available (previous initialization failed).")

            self._init_attempted = True
            logger.info(
                "Initializing STT Service (faster-whisper) with model='%s', language='%s'...",
                self.model_name,
                self.language,
            )
            if WhisperModel and BatchedInferencePipeline:
                try:
                    self._model = WhisperModel(self.model_name, device="cpu", compute_type="int8")
                    self.batched_model = BatchedInferencePipeline(model=self._model)
                    logger.info("STT Service initialized successfully with BatchedInferencePipeline.")
                except Exception as e:
                    logger.error(f"Failed to initialize STT Service: {e}")
                    self.batched_model = None
                    raise RuntimeError(f"STT initialization failed: {e}") from e
            else:
                raise RuntimeError("STT Service not available (faster-whisper not installed).")

    def transcribe(self, file_path: str) -> str:
        """
        Transcribe audio file to text using faster-whisper.
        Raises Exception if STT fails or not available.
        """
        self._ensure_initialized()

        if not os.path.exists(file_path):
            raise FileNotFoundError(f"Audio file not found: {file_path}")

        try:
            logger.info(f"Transcribing file: {file_path}")
            # Use batched transcription
            # User requested: batch_size=8
            segments, info = self.batched_model.transcribe(
                file_path, 
                batch_size=8,
                language=self.language
            )
            
            # Gather segments
            text_segments = []
            for segment in segments:
                text_segments.append(segment.text)
                
            full_text = "".join(text_segments)
            logger.info(f"Transcription result: {full_text}")
            return full_text
        except Exception as e:
            logger.error(f"Transcription error: {e}")
            raise e
