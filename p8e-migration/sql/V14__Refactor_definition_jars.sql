DELETE FROM definition;
DROP INDEX definition_target_type_target_id_idx;
ALTER TABLE definition DROP CONSTRAINT definition_pkey;

ALTER TABLE definition RENAME to class_resource;
ALTER TABLE class_resource DROP COLUMN target_type;
ALTER TABLE class_resource DROP COLUMN updated;
ALTER TABLE class_resource RENAME COLUMN target_id TO class_reference_type;
ALTER TABLE class_resource ADD COLUMN affiliate_uuid UUID NOT NULL;
ALTER TABLE class_resource RENAME COLUMN definition_uuid TO definition_resource_uuid;
ALTER TABLE class_resource ADD PRIMARY KEY (definition_resource_uuid);

CREATE INDEX class_resource_affiliate_uuid_idx on class_resource (affiliate_uuid);
CREATE INDEX class_resource_class_reference_type_idx on class_resource (class_reference_type);
CREATE INDEX class_resource_hash_idx on class_resource (hash);