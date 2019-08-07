#
# we start from plain old Java 8
#
FROM openjdk:8-alpine

# now we install SSHD
# default user/pwd is biocache/biocache
ARG BIOPWD=biocache
RUN apk add --update dropbear tini curl tmux && \
    addgroup biocache && \
	adduser -G biocache -D biocache && \
    mkdir -p /etc/dropbear && \
	echo "biocache:$BIOPWD" | chpasswd
#	adduser -G biocache -G wheel -D biocache && \

#ARG DIST_URL=https://nexus.ala.org.au/service/local/repositories/releases/content/au/org/ala/biocache-store/2.4.4/biocache-store-2.4.4-distribution.zip
#ARG DIST_URL=https://ala-rnp.s3.amazonaws.com/ala-assets/brasil/biocache-store-2.4.5-SNAPSHOT-distribution.zip
ARG DIST_URL=http://68.183.146.85:6000/biocache-store/target/biocache-store-2.4.5-SNAPSHOT-distribution.zip
ARG DIST_VERSION=biocache-store-2.4.5-SNAPSHOT
# BIOCACHE CLI
RUN mkdir -m 0774 -p \
	/data/ala/layers/ready/shape \
	/data/biocache-load \
	/data/biocache-media \
	/data/biocache-upload \
	/data/biocache-delete \
	/data/cache \
	/data/tmp \
	/data/offline/exports \
	/data/tool
RUN wget "$DIST_URL" -q -O /tmp/biocache.zip && \
	unzip /tmp/biocache.zip -d /usr/lib/ && \
    mv "/usr/lib/$DIST_VERSION" /usr/lib/biocache && \
    ln -s /usr/lib/biocache/bin/biocache /usr/bin/biocache && \
	rm /tmp/biocache.zip
# DEFAULT PROPERTIES
# (should work with minimal set of modules, best if localhost)
COPY ./config/biocache-config.properties /data/biocache/config/biocache-config.properties
# MOVE THIS INTO ENTRYPOINT (CONDITIONAL LOADING)
# (each setup my have its own namematching)
ARG NAME_MATCHING_URL=https://s3.us-east-2.amazonaws.com/sibbr-ala/namematching.zip
RUN wget "$NAME_MATCHING_URL" -q -O /opt/namematching.zip && \
    mkdir /data/lucene && \
    unzip /opt/namematching.zip -d /data/lucene && \
	rm /opt/*.zip && \
    wget https://github.com/AtlasOfLivingAustralia/ala-install/raw/master/ansible/roles/biocache-properties/files/subgroups.json -q -O /data/biocache/config/subgroups.json && \
	chown -R biocache:biocache /data

# Install ttyd too
RUN apk add --update ttyd
COPY ./scripts/welcome.txt /opt/

# smart entrypoint
COPY ./scripts/entrypoint.sh /opt/
RUN chmod +x /opt/entrypoint.sh


ENTRYPOINT ["tini", "--","/opt/entrypoint.sh"]
#ENTRYPOINT ["/opt/entrypoint.sh"]
#CMD ["/usr/sbin/dropbear","-j","-k","-E","-F","-R","-s"]

EXPOSE 22 7681
