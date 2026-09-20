-- Bitmap subtitles (Blu-ray PGS, DVD VobSub, DVB) are no longer OCR'd into SRT files: the
-- transcoder serves their pictures to the player as sprite sheets at playback time.
--
-- Remove the OCR results: every EXTERNAL_SUBTITLE row that was derived from a bitmap stream
-- (same media file, same stream index) and carries the extractor's file name
-- ({mediaFileId}_{streamIndex}_{lang}.srt). Sidecar .srt files and SRTs extracted from text
-- streams match neither and stay. The SRT files themselves are left to the cache cleanup,
-- which deletes cache files no row references.
DELETE FROM media_file_stream_entity ocr
USING media_file_stream_entity source
WHERE ocr.codec_type = 'EXTERNAL_SUBTITLE'
  AND source.codec_type = 'SUBTITLE'
  AND source.media_file_entity_id = ocr.media_file_entity_id
  AND source.stream_index = ocr.stream_index
  AND ocr.path LIKE '%' || ocr.media_file_entity_id::text || '\_' || ocr.stream_index::text || '\_%.srt'
  AND lower(source.codec_name) IN ('dvd_subtitle', 'dvdsub', 'hdmv_pgs_subtitle', 'pgssub', 'dvb_subtitle');

-- The failure marker only ever meant "OCR failed" for these streams.
UPDATE media_file_stream_entity
SET extraction_failed = NULL
WHERE codec_type = 'SUBTITLE'
  AND extraction_failed IS NOT NULL
  AND lower(codec_name) IN ('dvd_subtitle', 'dvdsub', 'hdmv_pgs_subtitle', 'pgssub', 'dvb_subtitle');
