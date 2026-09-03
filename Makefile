# Makefile
.PHONY: chaos-test chaos-test-01 chaos-test-02 chaos-test-03 chaos-test-04 chaos-test-05 up down smoke-test

up:
	docker compose up -d --build

down:
	docker compose down -v

smoke-test:
	bash scripts/smoke-test.sh

chaos-test-01:
	bash chaos/scenarios/01_rabbitmq_down_mid_publish.sh

chaos-test-02:
	bash chaos/scenarios/02_ledger_db_crash_post_commit.sh

chaos-test-03:
	bash chaos/scenarios/03_processor_crash_mid_consume.sh

chaos-test-04:
	bash chaos/scenarios/04_duplicate_delivery.sh

chaos-test-05:
	bash chaos/scenarios/05_partition_during_lock.sh

chaos-test: chaos-test-01 chaos-test-02 chaos-test-03 chaos-test-04 chaos-test-05
	@echo "All chaos scenarios passed."
