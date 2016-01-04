#!/bin/bash

if [ -z "${JAVA_HOME}" ]
then
    echo "Please set environment JAVA_HOME";
    exit 1
fi

echo "env: "${env}

em1="$(/sbin/ifconfig | grep -A 1 'em1' | tail -1 | grep "inet addr:" | cut -d ':' -f 2 | cut -d ' ' -f 1)"
em2="$(/sbin/ifconfig | grep -A 1 'em2' | tail -1 | grep "inet addr:" | cut -d ':' -f 2 | cut -d ' ' -f 1)"

export cluster_hostname=$(if [[ -z "$em2" ]]; then echo "127.0.0.1"; else echo ${em2}; fi)

main_class=com.wandoujia.statuscentre.PushStandaloneServer
module=push_ctr_standalone
jmx_port=2999

server_conf=../conf/${module}.conf
server_lock_file=.lock_${module}
logback_conf=../conf/logback_${module}.xml

export JAVA=${JAVA_HOME}/bin/java
export FLAGS="-server -Dfile.encoding=UTF8 -XX:+UseNUMA -XX:+UseCondCardMark -XX:-UseBiasedLocking"
export JMX="-Djava.rmi.server.hostname=$cluster_hostname -Dcom.sun.management.jmxremote.port=$jmx_port -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false"
export HEAP="-Xms22528M -Xmx22528M -Xss1M -XX:MaxMetaspaceSize=256m" # 22G
export GC="-XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:+UnlockExperimentalVMOptions -Xloggc:../log/gc-${module}.log -XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=3 -XX:GCLogFileSize=100M "

NOW=$(date +"%Y-%m-%dT%H%M%S")

CP="";
for f in ../lib/*.jar;
    do CP=${f}":"${CP};
done;

$JAVA $FLAGS $HEAP $GC $JMX -Dconfig.file=${server_conf} -Dlogback.configurationFile=${logback_conf} -cp ${CP} ${main_class} > ../log/${module}_${NOW}_rt.log &
SERVER_PID=$!
echo $SERVER_PID > ./${server_lock_file}
echo "Started standalone, pid is $SERVER_PID"
