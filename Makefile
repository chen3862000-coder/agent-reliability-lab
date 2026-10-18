.PHONY: verify test format start run live-test harness-test

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

live-test:
	./mvnw -pl arl-core -Darl.live=true -Dtest=LiveProviderTest test
