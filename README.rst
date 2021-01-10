================================================================================
  OLD Pyramid Deployment (OPD) using Docker and Babashka
================================================================================

.. warning:: I am currently (2021-01-09) considering moving away from this
             heavily automated approach and starting fresh, but building on this.

This project deploys the Online Linguistic Database (OLD) using Docker images
and containers, and Babashka for shell scripting and service deployment
orchestration.

It should ultimately be able to automate the installation of both Pylons and
Pyramid OLD installations. Note that while the "original" (Pylons) version of
the OLD is officially deprecated, some OLD instances are still running on it as
of this writing (2020-08-26.)


TODOs
================================================================================

- Write deployment scripts for the Pyramid OLD.
- Move this code to the OLD repository itself and set up CI/CD with CircleCI.
- Set up log shipping.
- Set up automated backups of MySQL and filesystem data.
- Set up monitoring for downtime.
- Ensure reboots bring the system back up.
- Store images in Docker Hub.


How to Deploy the OLD using the OPD (this tool)
================================================================================

The OPD can be used to deploy both versions of the OLD, the Pylons version and
the Pyramid one. Some steps are common to both versions. These are given below.
When the steps diverge, new sections begin.

First, clone this repo (the OPD source)::

    $ git clone https://github.com/dativebase/old-pyramid-deployment
    $ cd old-pyramid-deployment

Next, clone the source code for the Pyramid and Pylons OLDs to
``src/old-pyramid/`` and ``src/old/``, respectively::

    $ git submodule update --init --recursive

TODO: fix the following paragraph to reflect the Pylons/Pyramid split reality.

This project assumes that the ``private/`` directory contains two sub-directories
with analogous structures. The ``private/old-stores/`` directory should contain
subdirectories whose names are the OLD instances (and simultaneously the names of
the databases of those instances) of each OLD being deployed. The
``private/old-mysqldumps/`` directory should contain one MySQL dump file for each
OLD instance directory mentioned above; each file should have the same name as
the directory, but with the ``.dump`` extension. Example::

    $ ls private/old-stores/
    blaold
    dpold
    $ ls private/old-mysqldumps/
    blaold.dump
    dpold.dump


How to Deploy the Pylons OLD
================================================================================

The following command will build Docker images for MySQL, the Pylons OLD and
Nginx and then bring up a container running MySQL, one running Nginx, and one
for each Pylons OLD under ``private/old-stores/``::

    $ bb opd.clj deploy-new-pylons <ENV>

The value of ``<ENV>`` determines which top-level key under ``:environments`` in
the config map of ``CONFIG.edn`` will be used.

The above command is equivalent to running the following commands in sequence.

1. Create the ``opd`` Docker network idempotently::

       $ bb opd.clj create-network <ENV>

2. Build the MySQL image and bring up a container based on it named
   ``opd-mysql``. This container will have access to ``private/mysqldumps/`` from
   which it can ingest the source OLD MySQL dump files. It will also write its
   MySQL persistence files to ``private/mysql-data`` on the host. To create the
   MySQL container idempotently::

       $ bb opd.clj up-build-mysql <ENV>

3. Create the Pylons OLD MySQL databases in the running MySQL container. Running
   the following will create one database for each dump file in
   ``private/mysqldumps/``. Warning: this will drop any database in the MySQL
   container that has the same name as any of the dump files in the
   aforementioned directory.::

       $ bb opd.clj bootstrap-dbs <ENV>

4. Build the OLD Docker image::

       $ bb opd.clj build-old <ENV>

5. Repair the Pylons OLD config files. These are the ``production.ini`` files
   under each subdirectory of ``private/old-stores``, e.g.,
   ``private/old-stores/blaold/production.ini``. This sets the host value to
   ``0.0.0.0`` and the ``sqlalchemy.url`` value to
   ``mysql://<MYSQL_USER>:<MYSQL_PASSWORD>@<MYSQL_CONTAINER>:3306/<OLD_DB_NAME>?charset=utf8``.::

       $ bb opd.clj repair-old-configs <ENV>

6. Bring up the OLD containers. Each OLD container will have a name like
   ``opd-old-<OLD_NAME>``, where ``<OLD_NAME>`` is the name of the OLD dump
   file. Note that this is idempotent insofar as a container will not be spun up
   if one with the same name already exists::

       $ bb opd.clj up-olds <ENV>

7. Build the Nginx Docker image and create a container from it named
   ``opd-nginx``. The Nginx process within this container will be configured to
   serve OLDs (using the HTTPS scheme at port 443) at subpaths mathing the OLD
   names, i.e., ``https://<DOMAIN>:443/<SUBPATH>/``, e.g.,
   ``https://oldpyramid:443/blaold/``. This Nginx config file is visible on the
   host at ``src/nginx/etc/old``. Its contents are determined by inspecting the
   OLD config files that were repaired in a previous step above::

        $ bb opd.clj up-build-nginx <ENV>


How to Deploy the Pyramid OLD
================================================================================

The following command will build Docker images for MySQL, the Pyramid OLD and
Nginx and then bring up a container running MySQL, one running Nginx, and one
for each Pyramid OLD under ``private/old-stores-pyr/``::

    $ bb opd.clj deploy-new-pyramid <ENV>

The value of ``<ENV>`` determines which top-level key under ``:environments`` in
the config map of ``CONFIG.edn`` will be used.

The above command is equivalent to running the following commands in sequence.

1. Create the ``opd`` Docker network idempotently::

       $ bb opd.clj create-network <ENV>

2. Build the MySQL image and bring up a container based on it named
   ``opd-mysql-pyr``. This container will have access to
   ``private/mysqldumps-pyr/`` from which it can ingest the source OLD MySQL dump
   files. It will also write its MySQL persistence files to
   ``private/mysql-data-pyr`` on the host. To create the MySQL container idempotently::

       $ bb opd.clj up-build-mysql-pyr <ENV>

3. Create the Pyramid OLD MySQL databases in the running MySQL container. Running
   the following will create one database for each dump file in
   ``private/old-mysqldumps-pyr/``. Warning: this will drop any database in the MySQL
   container that has the same name as any of the dump files in the
   aforementioned directory.::

       $ bb opd.clj bootstrap-dbs-pyr <ENV>

4. Build the OLD Pyramid Docker image::

       $ bb opd.clj build-old-pyr <ENV>

5. Repair the Pyramid OLD config files. These are the ``production.ini`` files
   under each subdirectory of ``private/old-stores-pyr``, e.g.,
   ``private/old-stores-pyr/cooold/production.ini``. This sets the host value to
   ``0.0.0.0`` and the ``sqlalchemy.url`` value to
   ``mysql://<MYSQL_USER>:<MYSQL_PASSWORD>@<MYSQL_CONTAINER>:3306/<OLD_DB_NAME>?charset=utf8``.::

       $ bb opd.clj repair-old-pyr-configs <ENV>

   .. note:: I do not believe the above step is necessary with Pyramid OLDs.
             These OLDs use the in-source configuration file ``config.ini`` and
             customize it with environment variables.

6. Bring up the OLD containers. Each OLD container will have a name like
   ``opd-old-pyr-<OLD_NAME>``, where ``<OLD_NAME>`` is the name of the OLD dump
   file. Note that this is idempotent insofar as a container will not be spun up
   if one with the same name already exists::

       $ bb opd.clj up-olds-pyr <ENV>

7. Build the Nginx Docker image and create a container from it named
   ``opd-pyr-nginx``. The Nginx process within this container will be configured
   to serve OLDs (using the HTTPS scheme at port 443) at subpaths mathing the OLD
   names, i.e., ``https://<DOMAIN>:443/<SUBPATH>/``, e.g.,
   ``https://oldpyramid:443/blaold/``. This Nginx config file is visible on the
   host at ``src/nginx/etc/old``. Its contents are determined by inspecting the
   OLD config files that were repaired in a previous step above::

        $ bb opd.clj up-build-nginx-pyr <ENV>

        Network connectivity blocked by security group rule: DefaultRule_DenyAllInBound


Notes about the Deployments
================================================================================

OLD instance container logs can be found under::

    $ private/old-stores/<OLD_NAME>/application.log

e.g.,::

    $ private/old-stores/gitold/application.log

.. warning:: It appears to me that the types of paths listed above do not
             actually contain any useful logs. I believe the most interesting
             logs (at least in the Pylons OLDs) are the Nginx access logs. See
             below.

The Nginx container's logs are under ``/var/lib/docker/containers/`` in a
directory and file named using the container's ID, which can be found as
follows::

    $ docker ps --no-trunc -aqf "name=opd-nginx"
    1b7623bda2621d6135ad71cbaaed515f0bb373a88d875fd568ce8cd2beeb5edc

In this case, the logs are at::

    /var/lib/docker/containers/1b7623bda2621d6135ad71cbaaed515f0bb373a88d875fd568ce8cd2beeb5edc/1b7623bda2621d6135ad71cbaaed515f0bb373a88d875fd568ce8cd2beeb5edc-json.log

New filesystem data (e.g., audio files, phonologies) will be stored under::

    $ ls private/old-stores/

The MySQL data directory is mounted to the host at::

    $ ls private/mysql-data/




WARNING: Beware Below!
================================================================================

Take all of the documentation below with a grain of salt. It needs review.


Usage
================================================================================

This tutorial assumes that you are running on a system with Docker installed.

High-level Steps:

1. Clone the OPD source.
2. Clone the OLD Pyramid source.
2. Build and run the ``opd`` Docker daemon container
3. Use ``opd`` to download and install the Babashka ``bb`` script to the host.
4. Use ``bb`` to:

   a. Build Docker images for the required services:

      i. MySQL
      ii. OLD Pyramid
      iii. Nginx (web server)

   d. Launch long-running dockerized processes for the configuration defined in
      opd.edn. In overview, launch processes running:

      i. MySQL (listening on port 3306)
      ii. Nginx (listening on ports 80 and 443)


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
    $ cd /home/rancher/downloads
    $ unzip bb.zip
    $ mv bb /usr/bin/bb


Build the MySQL Docker image
--------------------------------------------------------------------------------

Use ``bb`` to build the MySQL Docker image::

    $ bb opd.clj build-mysql


