ALTER TABLE standard.dictionary_export
    DROP CONSTRAINT dictionary_export_entry_count_check,
    ADD CONSTRAINT dictionary_export_entry_count_check CHECK (artcl_cnt BETWEEN 0 AND 10000);
