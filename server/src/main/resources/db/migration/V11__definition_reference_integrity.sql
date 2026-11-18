ALTER TABLE catalog.dataset ADD CONSTRAINT dataset_owner_identity UNIQUE(owner_organization_id,id);
ALTER TABLE standard.definition_import ADD CONSTRAINT definition_import_dataset_owner
    FOREIGN KEY(organization_id,dataset_id) REFERENCES catalog.dataset(owner_organization_id,id) ON DELETE RESTRICT;
ALTER TABLE standard.definition_application ADD CONSTRAINT definition_application_revision_dataset
    FOREIGN KEY(dataset_id,revision_id) REFERENCES catalog.dataset_revision(dataset_id,id) ON DELETE RESTRICT;
CREATE UNIQUE INDEX analysis_one_per_definition_import ON analytics.analysis_run(definition_import_id) WHERE definition_import_id IS NOT NULL;

CREATE FUNCTION standard.validate_definition_application() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS(SELECT 1 FROM standard.definition_import i JOIN catalog.dataset d ON d.owner_organization_id=i.organization_id
        WHERE i.id=NEW.import_id AND d.id=NEW.dataset_id AND (i.dataset_id IS NULL OR i.dataset_id=d.id)) THEN
        RAISE EXCEPTION 'Definition application organization or dataset mismatch' USING ERRCODE='23514';
    END IF;
    IF jsonb_array_length(NEW.selected_field_ids) NOT BETWEEN 1 AND 500
       OR (SELECT count(DISTINCT selected.value) FROM jsonb_array_elements_text(NEW.selected_field_ids) selected(value)) <> jsonb_array_length(NEW.selected_field_ids)
       OR EXISTS(SELECT 1 FROM jsonb_array_elements_text(NEW.selected_field_ids) selected(value)
           WHERE NOT EXISTS(SELECT 1 FROM standard.definition_field f WHERE f.import_id=NEW.import_id AND f.id::text=selected.value)) THEN
        RAISE EXCEPTION 'Definition application selection does not belong to source import' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER definition_application_source_guard BEFORE INSERT ON standard.definition_application
    FOR EACH ROW EXECUTE FUNCTION standard.validate_definition_application();

CREATE FUNCTION analytics.validate_analysis_scope() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.dataset_revision_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM catalog.dataset_revision r
        JOIN catalog.dataset d ON d.id=r.dataset_id WHERE r.id=NEW.dataset_revision_id AND d.owner_organization_id=NEW.organization_id) THEN
        RAISE EXCEPTION 'Analysis revision organization mismatch' USING ERRCODE='23514';
    END IF;
    IF NEW.definition_import_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM standard.definition_import i
        WHERE i.id=NEW.definition_import_id AND i.organization_id=NEW.organization_id) THEN
        RAISE EXCEPTION 'Analysis import organization mismatch' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER analysis_scope_guard BEFORE INSERT ON analytics.analysis_run
    FOR EACH ROW EXECUTE FUNCTION analytics.validate_analysis_scope();
