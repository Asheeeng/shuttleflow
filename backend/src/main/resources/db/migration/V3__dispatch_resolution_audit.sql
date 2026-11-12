ALTER TABLE dispatch_task ADD COLUMN resolved_at TIMESTAMP(6);
ALTER TABLE dispatch_task ADD COLUMN resolved_by BIGINT;
ALTER TABLE dispatch_task ADD COLUMN resolution_note VARCHAR(300);
ALTER TABLE dispatch_task ADD CONSTRAINT fk_dispatch_resolver FOREIGN KEY (resolved_by) REFERENCES app_user(id);
