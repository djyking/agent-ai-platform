# Put the two official release files below alongside this Dockerfile.
# The caller reviews provenance before downloading; digests reject changed assets.
FROM mysql:8.4@sha256:b3b90af2a6552ae30c266fdb7d5dd55f3afb72404bb78d37fe8a23eb857fd3fb
USER root
RUN microdnf install -y python3.11 cpio gzip && microdnf clean all
COPY mysql-community-client-8.4.11-1.el9.x86_64.rpm /tmp/client.rpm
RUN echo "2f1e8317c557363dd1554b29a9f6d69403802dc623fe72d9995f20f542771bb1  /tmp/client.rpm" | sha256sum -c - \
    && rpm --import /etc/pki/rpm-gpg/RPM-GPG-KEY-mysql \
    && rpm -K /tmp/client.rpm \
    && mkdir /tmp/client && cd /tmp/client \
    && rpm2cpio /tmp/client.rpm | cpio -id ./usr/bin/mysqlbinlog \
    && cp usr/bin/mysqlbinlog /usr/local/bin/mysqlbinlog
COPY age-v1.3.2-linux-amd64.tar.gz /tmp/age.tar.gz
RUN echo "cbe24006683f8eb669266162894b9a522a1af52f2665fbc63a4bb032ed26ac10  /tmp/age.tar.gz" | sha256sum -c - \
    && tar -xzf /tmp/age.tar.gz -C /tmp \
    && cp /tmp/age/age /tmp/age/age-keygen /usr/local/bin/
ENTRYPOINT ["python3.11"]
