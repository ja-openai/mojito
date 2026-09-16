# Build context: dev-tools/storage-retention. No floating image or tool defaults.
ARG MOJITO_IMAGE
FROM ${MOJITO_IMAGE}
ARG MOJITO_IMAGE
ARG PYTHON3_PACKAGE_VERSION
ARG CA_CERTIFICATES_PACKAGE_VERSION
ARG TARGETARCH
ARG KUBECTL_VERSION
ARG KUBECTL_SHA256
USER root
RUN test -n "$PYTHON3_PACKAGE_VERSION" && test -n "$CA_CERTIFICATES_PACKAGE_VERSION" && \
    apt-get update && apt-get install -y --no-install-recommends \
      "python3=$PYTHON3_PACKAGE_VERSION" "ca-certificates=$CA_CERTIFICATES_PACKAGE_VERSION" && \
    rm -rf /var/lib/apt/lists/*
COPY maintenance/install_kubectl.py /tmp/install_kubectl.py
RUN python3 /tmp/install_kubectl.py "$MOJITO_IMAGE" "$TARGETARCH" "$KUBECTL_VERSION" "$KUBECTL_SHA256" && \
    rm /tmp/install_kubectl.py && \
    test -f /usr/local/mojito/bin/mojito-webapp.jar
COPY verify-maintenance-fence.py /opt/mojito-maintenance/verify-maintenance-fence.py
COPY maintenance/entrypoint.py maintenance/verify_fence.py /opt/mojito-maintenance/
RUN chmod 0555 /opt/mojito-maintenance/*.py && \
    python3 -m py_compile /opt/mojito-maintenance/*.py && \
    kubectl version --client=true --output=json
ENV PYTHONDONTWRITEBYTECODE=1 HOME=/tmp/maintenance-home \
    MOJITO_MAINTENANCE_START=false MOJITO_MAINTENANCE_PROMOTION_ENABLED=false
WORKDIR /tmp
USER 10001:10001
ENTRYPOINT ["python3", "/opt/mojito-maintenance/entrypoint.py"]
