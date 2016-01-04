#!/bin/bash

if [ -z "${JAVA_HOME}" ]
then
    echo "Please set environment JAVA_HOME";
    exit 1
fi

echo "env: "${env}

em1="$(/sbin/ifconfig | grep -A 1 'em1' | tail -1 | grep "inet addr:" | cut -d ':' -f 2 | cut -d ' ' -f 1)"
em2="$(/sbin/ifconfig | grep -A 1 'em2' | tail -1 | grep "inet addr:" | cut -d ':' -f 2 | cut -d ' ' -f 1)"

class_pgm="com.wandoujia.statuscentre.inbound.PushCtrInbound"
cluster_module="push_ctr_inbound"
cluster_hostname=$(if [[ -z "$em2" ]]; then echo "127.0.0.1"; else echo ${em2}; fi)
jmx_port=2998

id_pgm=${cluster_module}
lock_file=.lock_${cluster_module}
logback_conf=../conf/logback_${cluster_module}.xml

export JAVA=${JAVA_HOME}/bin/java
export FLAGS="-server -Dfile.encoding=UTF8 -XX:+UseNUMA -XX:+UseCondCardMark -XX:-UseBiasedLocking"
export JMX="-Djava.rmi.server.hostname=$cluster_hostname -Dcom.sun.management.jmxremote.port=$jmx_port -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false"
export HEAP="-Xms1024M -Xmx3072M -Xss1M -XX:MaxMetaspaceSize=256m"
export GC="-XX:+UseParallelOldGC -Xloggc:../log/gc-$cluster_module.log -XX:+PrintGCDetails -XX:+PrintGCDateStamps -XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles=3 -XX:GCLogFileSize=100M -XX:MaxGCPauseMillis=3500"

NOW=$(date +"%Y-%m-%dT%H%M%S")

cp="";
for f in ../lib/*.jar;
do cp=${f}":"${cp};
done;

$JAVA $FLAGS $HEAP $GC $JMX -Dlogback.configurationFile=${logback_conf} -Dconfig.file=../conf/push_ctr_inbound.conf -Dakka.remote.netty.tcp.hostname=${cluster_hostname} -cp ${cp} ${class_pgm} > ../log/${cluster_module}_${NOW}_rt.log &
pid=$!
echo $pid > ./${lock_file}
echo "Started ${id_pgm}, pid is $pid"
