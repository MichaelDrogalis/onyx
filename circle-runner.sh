#!/bin/bash

set -e


if [ -z "$CIRCLE_BRANCH" ]; then
	export BR=$CI_BRANCH
else
	export BR=$CIRCLE_BRANCH
fi

if [ $BR == "master" ]; then
	export TEST_CHECK_FACTOR=5
fi

i=0
files=""

TEST_NSES_NONGEN=$(find test -name "*.clj" |sed s/test\\///|sed s/\\//\\./g|sed s/".clj$"//|sed s/"_"/"-"/g|grep -v gen | tr '\n' ' '|sort)
TEST_NSES_GENERATIVE=$(find test -name "*.clj" |sed s/test\\///|sed s/\\//\\./g|sed s/".clj$"//|sed s/"_"/"-"/g | grep gen | tr '\n' ' '|sort)

for file in $TEST_NSES_NONGEN
do
  if [ $(($i % $CIRCLE_NODE_TOTAL)) -eq $CIRCLE_NODE_INDEX ]
  then
    files+=" $file"
  fi
  ((++i))
done

# Run generative tests on all nodes to more evently distribute run time
# If they're taking too long we can reduce the TEST_CHECK_FACTOR
files+=" "$TEST_NSES_GENERATIVE

echo "Running " $files

export TEST_TRANSPORT_IMPL=$1 

ARTIFACT_DIR=$CIRCLE_BUILD_NUM/$CIRCLE_NODE_INDEX/$BR"_"$1

mkdir -p log_artifact/$ARTIFACT_DIR/

#lein with-profile dev,circle-ci jammin 360 midje $files |& tee log_artifact/$ARTIFACT_DIR/stderrout.log
lein with-profile dev,circle-ci midje $files |& tee log_artifact/$ARTIFACT_DIR/stderrout.log

EXIT_CODE=${PIPESTATUS[0]}

cp onyx.log* log_artifact/$ARTIFACT_DIR/ && bzip2 -9 recording.jfr && cp recording.jfr.bz2 log_artifact/$ARTIFACT_DIR/ && aws s3 sync log_artifact/$ARTIFACT_DIR s3://onyxcircleresults/$ARTIFACT_DIR && rm recording.jfr.bz2 || echo "FAILED AWS UPLOAD"

exit $EXIT_CODE
