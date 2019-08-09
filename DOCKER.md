Dockerized biocache-store
======

This image holds a biocache-store runtime inside a container running SSHD and Java.

## SSHD/TTYD

Current biocache workflow assumes a command-line prompt environment. This is usually done with a remote VM, but we need something better when using containers.

This image runs SSHD (dropbear) with a few possible options as env vars:

* BIOPWD: biocache user password
* PUBLICKEY: a SSH public that will be copied into biocache user's `~/.ssh/authorized_keys` (this disables password login)
* USETTYD: does not start SSHD but TTYD instead (port 7681)

TODO

* Move namematching setup into entrypoint (must be dynamic)

## Debugging biocache with Docker

### Running IDE and biocache locally (mount classes inside container, set debug options)

Modify docker-compose in order to mount classes and debug options:

```
    environment:
    ...
      # JAVA DEBUG OPTIONS
      - JAVA_OPTS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005
    volumes:
      # JAVA DEBUG: mount classes at "/usr/bin/biocache/etc" (already on classpath)
      - ./target/classes:/usr/lib/biocache/etc
```

Start the biocache utility inside the container and attach the debugger.

Optional: start Cassandra in a local container separately in advance:

```sh
docker-compose up -d cassandradb
```
