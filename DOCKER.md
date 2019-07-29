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
