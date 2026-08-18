-- Marks a subtitle stream whose extraction to SRT was attempted during analysis but
-- failed (e.g. subtile-ocr cannot read the bitmap subtitle, or no usable OCR language).
--
-- NULL = never attempted / not applicable. The scanner's subtitle re-extract backfill
-- skips streams marked TRUE, so a permanently failing OCR no longer re-triggers a full
-- re-analysis on every scan. A re-analysis (for any other reason) rewrites the stream
-- rows and so retries the extraction from scratch.
ALTER TABLE media_file_stream_entity
    ADD COLUMN IF NOT EXISTS extraction_failed BOOLEAN;
