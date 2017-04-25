FROM java:8-jre-alpine

ADD target/duke-microservice-1.0-SNAPSHOT.jar /srv/

EXPOSE 4567
ENTRYPOINT ["java", "-jar", "/srv/duke-microservice-1.0-SNAPSHOT.jar"]


