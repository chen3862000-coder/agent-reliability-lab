.PHONY: verify test format start run evaluate live-test harness-test

verify:
	python3 scripts/verify.py

test: verify

format:
	./mvnw spotless:apply

harness-test:
	python3 -m unittest discover -s scripts -p 'test_*.py' 

start:
	./scripts/start-arl-server.sh

run: start

evaluate:
	./mvnw -pl arl-evaluation -am test dependency:build-classpath -Dmdep.outputFile=target/evaluation/classpath.txt
	java -cp "arl-evaluation/target/classes:arl-core/target/classes:$$(cat arl-evaluation/target/evaluation/classpath.txt)" dev.arl.evaluation.EvaluationMain "$(CURDIR)/target/evaluation/report.json"

live-test:
	./mvnw -pl arl-core -Darl.live=true -Dtest=LiveProviderTest test
