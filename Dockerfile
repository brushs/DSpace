# This image will be published as dspace/dspace
# See https://github.com/DSpace/DSpace/tree/main/dspace/src/main/docker for usage details
#
# This version is JDK11 compatible
# - tomcat:8-jdk11
# - ANT 1.10.7
# - maven:3-jdk-11 (see dspace-dependencies)
# - note: default tag for branch: dspace/dspace: dspace/dspace:dspace-7_x

# Step 1 - Run Maven Build
FROM dspace/dspace-dependencies:dspace-7_x as build
ARG TARGET_DIR=dspace-installer
WORKDIR /app

# The dspace-install directory will be written to /install
RUN mkdir /install \
    && chown -Rv dspace: /install \
    && chown -Rv dspace: /app

USER dspace

# Copy the DSpace source code into the workdir (excluding .dockerignore contents)
ADD --chown=dspace . /app/
COPY dspace/src/main/docker/local.cfg /app/local.cfg

# Build DSpace (note: this build doesn't include the optional, deprecated "dspace-rest" webapp)
# Copy the dspace-install directory to /install.  Clean up the build to keep the docker image small
RUN mvn package && \
  mv /app/dspace/target/${TARGET_DIR}/* /install && \
  mvn clean

# Step 2 - Run Ant Deploy
FROM tomcat:8-jdk11 as ant_build
ARG TARGET_DIR=dspace-installer
COPY --from=build /install /dspace-src
WORKDIR /dspace-src

# Create the initial install deployment using ANT
ENV ANT_VERSION 1.10.7
ENV ANT_HOME /tmp/ant-$ANT_VERSION
ENV PATH $ANT_HOME/bin:$PATH

RUN mkdir $ANT_HOME && \
    wget -qO- "https://archive.apache.org/dist/ant/binaries/apache-ant-$ANT_VERSION-bin.tar.gz" | tar -zx --strip-components=1 -C $ANT_HOME

RUN ant init_installation update_configs update_code update_webapps

# Step 3 - Run tomcat
# Create a new tomcat image that does not retain the the build directory contents
FROM tomcat:8-jdk11
ENV DSPACE_INSTALL=/dspace
COPY --from=ant_build /dspace $DSPACE_INSTALL
EXPOSE 8080 8009

ENV JAVA_OPTS=-Xmx2000m

# Run the "server" webapp off the /server path (e.g. http://localhost:8080/server/)
RUN ln -s $DSPACE_INSTALL/webapps/server   /usr/local/tomcat/webapps/server
# If you wish to run "server" webapp off the ROOT path, then comment out the above RUN, and uncomment the below RUN.
# You also MUST update the URL in dspace/src/main/docker/local.cfg
# Please note that server webapp should only run on one path at a time.
#RUN mv /usr/local/tomcat/webapps/ROOT /usr/local/tomcat/webapps/ROOT.bk && \
#    ln -s $DSPACE_INSTALL/webapps/server   /usr/local/tomcat/webapps/ROOT


# Install OpenSSH and set the password for root to "Docker!"
ENV SSH_PASSWD "root:Docker!"
RUN apt-get update \
        && apt-get install -y --no-install-recommends dialog \
        && apt-get update \
  && apt-get install -y --no-install-recommends openssh-server \
  && echo "$SSH_PASSWD" | chpasswd

# Copy the sshd_config file to the /etc/ssh/ directory
COPY sshd_config /etc/ssh/

# Copy and configure the ssh_setup file
RUN mkdir -p /tmp
COPY ssh_setup.sh /tmp
RUN chmod +x /tmp/ssh_setup.sh \
    && (sleep 1;/tmp/ssh_setup.sh 2>&1 > /dev/null)

# Open port 2222 for SSH access
EXPOSE 80 2222

CMD /usr/sbin/sshd && catalina.sh run
