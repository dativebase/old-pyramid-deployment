================================================================================
  OLD Pyramid Deployment (OPD) using Docker and Babashka
================================================================================

This project deploys the Online Linguistic Database (OLD) using Docker images
and containers, and Babashka for shell scripting and service deployment
orchestration.

It installs the version of the OLD written using the Python framework named
Pylons. This is the "original" OLD and is officially deprecated, but some OLD
instances are still running on it as of this writing (2020-08-26.)


Usage
================================================================================

This tutorial assumes that you are running on a system with Docker installed.

High-level Steps:

1. Clone the OPD source.
2. Clone the OLD Pyramid source.
2. Build and run the ``opd`` Docker daemon container
3. Use ``opd`` to donwload and install the Babashka ``bb`` script to the host.
4. Use ``bb`` to:
   b. Clone the OLD Pyramid source.
   c. Build Docker images for the required services:

      i. MySQL
      ii. OLD Pyramid
      iii. Nginx (web server)

   d. Launch long-running dockerized processes for the configuration defined in
      opd.edn. In overview, launch processes running:

      i. MySQL (listening on port 3306)
      ii. Nginx (listening on ports 80 and 443)


Clone the OPD source.
--------------------------------------------------------------------------------

First, clone the OPD source::

    $ git clone https://github.com/dativebase/old-pyramid-deployment
    $ cd old-pyramid-deployment


Build and run the ``opd`` Docker daemon container
--------------------------------------------------------------------------------

Build the ``opd:1.0`` (OLD Pyramid Deployment) image which brings in Babashka
(bb) and other system tools::

    $ docker build -t opd:1.0 .

Now run the container in daemon mode, mounting your home directory (which in this
example is /home/rancher/) to a mirror path in the container , and naming it
``opd``::

    $ docker run --rm -d -v "/home/rancher:/home/rancher" --name opd opd:1.0

Now you can execute tools such as ``bb`` and ``tree`` from within the ``opd``
container against files under your home directory. Example::

    $ docker exec -it opd bb
    Babashka v0.1.3 REPL.
    Use :repl/quit or :repl/exit to quit the REPL.
    Clojure rocks, Bash reaches.
    user=> (* 8 8)
    64

Optionally, set your shell profile to alias ``opd`` to ``docker exec -it opd``.
In RancherOS, for example, this would mean modifying ~/.profile as follows::

    $ cat ~/.profile
    alias opd="docker exec -it opd"
    $ source ~/.profile

Now we can just call ``opd <CMD>``, e.g.,::

    $ opd bb
    user=>

Now we can download the Babashka ``bb`` binary to the host machine, using cURL
from the OPD container::

    $ opd curl -s -L https://github.com/borkdude/babashka/releases/download/v0.1.3/babashka-0.1.3-linux-static-amd64.zip -o /home/rancher/downloads/bb.zip
