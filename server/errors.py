class CloudError(Exception):
    """Structured application error returned by the cloud API."""

    def __init__(self, code: str, message: str, status_code: int = 400):
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code


class ModelNotReadyError(CloudError):
    def __init__(self, message: str):
        super().__init__("MODEL_NOT_READY", message, 503)
