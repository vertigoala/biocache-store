#!/bin/ash

echo "Waiting for Cassandra..." # TODO: change hard coded name
/opt/wait-for-it.sh -h "cassandradb" -p 9042 -t 0 --strict -- echo "BIOCACHE ENTRYPOINT: cassandradb is up"

echo "BIOCACHE ENTRYPOINT: fixing biocache user..."
if [ ! -z "$BIOPWD" ]; then
    echo "biocache:$BIOPWD" | chpasswd
fi
# create .ssh folder
mkdir -p /home/biocache/.ssh
chmod 700 /home/biocache/.ssh

# copies public key if non-empty
PUBARG=""
if [ ! -z "$PUBLICKEY" ]; then
  echo "adjusting public key..."
  echo "$PUBLICKEY" > /home/biocache/.ssh/authorized_keys
  chmod 600 /home/biocache/.ssh/authorized_keys
  PUBARG=" -s"
fi

# fix permissions
chown -R biocache:biocache /home/biocache/.ssh

#if [ "$SUDOUSER" == "true" ]; then
#  echo "adjusting sudoers..."
#  echo "%wheel ALL=(ALL) NOPASSWD: ALL" >> /etc/sudoers
#fi

if [ "$USETTYD" == "true" ]; then
  echo "BIOCACHE ENTRYPOINT: opted out sshd, running ttyd on 7671 port..."
  exec su - biocache -c '/usr/bin/ttyd sh -c "cat /opt/welcome.txt; ash"'
# no args --> default cmd
elif [ $# -eq 0 ]; then
    echo "BIOCACHE ENTRYPOINT: Starting dropbear SSHD..."
    exec /usr/sbin/dropbear -j -k -E -F -R $PUBARG
else
    exec "$@"
fi
