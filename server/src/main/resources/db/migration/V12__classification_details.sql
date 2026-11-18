-- Category text remains administrative taxonomy metadata, separate from immutable publications.
ALTER TABLE classification.category ADD COLUMN description varchar(4000) NOT NULL DEFAULT '';
CREATE INDEX revision_category_dataset_version_idx ON catalog.dataset_revision(primary_category_id,dataset_id,revision_no DESC);
