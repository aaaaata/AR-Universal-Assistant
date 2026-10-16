FROM ac2-registry.cn-hangzhou.cr.aliyuncs.com/ac2/base:alinux3.2104-py312

WORKDIR /app

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONPATH=/app \
    YOLO_CONFIG_DIR=/tmp/Ultralytics \
    CLOUD_HOST=0.0.0.0 \
    CLOUD_PORT=8080 \
    CLOUD_WORKERS=3 \
    CLOUD_REGION_MANIFEST=/app/cloud/models/region.manifest.json \
    CLOUD_TILE_MANIFEST=/app/cloud/models/tile.manifest.json

COPY cloud/requirements.txt /app/cloud/requirements.txt

RUN mkdir -p /app/success_log /app/error_log /app/cloud/data \
    && touch /app/healthy \
    && if command -v yum >/dev/null 2>&1; then \
         yum install -y hostname libgomp mesa-libGL glib2 && yum clean all; \
       elif command -v apt-get >/dev/null 2>&1; then \
         apt-get update && apt-get install -y --no-install-recommends hostname libgomp1 libgl1 libglib2.0-0 && rm -rf /var/lib/apt/lists/*; \
       fi \
    && python -m pip install --no-cache-dir --upgrade pip \
    && python -m pip install --no-cache-dir -r /app/cloud/requirements.txt

COPY cloud /app/cloud
COPY tools /app/tools
COPY models/region/yolo26_hand_detect.pt /app/models/region/yolo26_hand_detect.pt
COPY models/tile/combined_original_and_new_data_best.pt /app/models/tile/combined_original_and_new_data_best.pt

EXPOSE 8080

CMD ["sh", "-c", "uvicorn cloud.app:app --host ${CLOUD_HOST:-0.0.0.0} --port ${CLOUD_PORT:-8080} --workers ${CLOUD_WORKERS:-3}"]
