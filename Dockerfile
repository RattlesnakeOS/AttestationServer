FROM gradle:jdk10 as builder
COPY --chown=gradle:gradle . /home/gradle/AttestationServer
WORKDIR /home/gradle/AttestationServer
RUN gradle build

FROM openjdk:10-jre-slim
EXPOSE 8080
VOLUME /data
COPY --from=builder /home/gradle/AttestationServer/build/distributions/AttestationServer.tar /app/
WORKDIR /app
RUN tar -xvf AttestationServer.tar
WORKDIR /app/AttestationServer
CMD bin/AttestationServer