=================
duke-microservice
=================

A Java microservice that uses the Duke deduplication engine (https://github.com/larsga/Duke).


To run it:

    docker run -p 4567:4567 knutj42/sesam-duke-microservice

Then open a browser on http://localhost:4567


To build a new version of the duke microservice:

    mvn clean install

    docker build -t knutj42/sesam-duke-microservice .


By default, the duke microservice uses the configuration in the file "src/main/resources/testdukeconfig.xml". This
file is also

This configuration can be overridden by setting the "CONFIG_STRING" environment variable to contain
the new configuration. It is also possible to manually upload a new configuration file via the microservice's
homepage (i.e. http://localhost:4567).


The file "sesam_node_example_config" in this folder contains a sesam node configuration that works
together with the default configuration of the duke-microservice.
