FROM discoenv/javabase

USER root
VOLUME ["/etc/iplant/de"]

COPY conf/main/logback.xml /home/iplant/
COPY target/notificationagent-standalone.jar /home/iplant/

ARG git_commit=unknown
ARG buildenv_git_commit=unknown
ARG version=unknown
LABEL org.iplantc.de.notificationagent.git-ref="$git_commit" \
      org.iplantc.de.notificationagent.version="$version" \
      org.iplantc.de.buildenv.git-ref="$buildenv_git_commit"

RUN ln -s "/opt/jdk/bin/java" "/home/iplant/bin/notificationagent"
ENTRYPOINT ["notificationagent", "-Dlogback.configurationFile=/etc/iplant/de/logging/notificationagent-logging.xml", "-cp", ".:notificationagent-standalone.jar", "notification_agent.core"]
CMD ["--help"]

