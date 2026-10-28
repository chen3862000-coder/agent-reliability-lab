.PHONY: verify test format start run lab-up lab-down lab-status lab-clean evaluate demo demo-cold demo-down frontend-install frontend-dev frontend-verify live-test harness-test

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

lab-up:
	./scripts/start-local-lab.sh

lab-down:
	./scripts/stop-local-lab.sh

lab-status:
	./scripts/status-local-lab.sh

lab-clean:
	./scripts/stop-local-lab.sh --clean

evaluate:
	./mvnw -pl arl-evaluation -am test dependency:build-classpath -Dmdep.outputFile=target/evaluation/classpath.txt
	java -cp "arl-evaluation/target/classes:arl-core/target/classes:$$(cat arl-evaluation/target/evaluation/classpath.txt)" dev.arl.evaluation.EvaluationMain "$(CURDIR)/target/evaluation/report.json"

frontend-install:
	npm --prefix frontend install

frontend-dev:
	npm --prefix frontend run dev

frontend-verify:
	npm --prefix frontend run verify
	npm --prefix frontend audit --audit-level=high

demo:
	docker compose up --build

demo-cold:
	docker compose build --no-cache
	docker compose up -d

demo-down:
	docker compose down

live-test:
	./mvnw -pl arl-core -Darl.live=true -Dtest=LiveProviderTest test
