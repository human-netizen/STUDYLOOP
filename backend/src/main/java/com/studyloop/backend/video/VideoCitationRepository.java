package com.studyloop.backend.video;

import com.studyloop.backend.chat.dto.Citation;
import com.studyloop.backend.document.DocumentSource;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// The scene-to-chunk links, written when a scene is planned and read back when the player asks
// what a scene was built from.
//
// Native SQL for the same reason ChunkSearchRepository is: the read is a three-table join whose
// output is a `Citation`, not an entity, and mapping it through JPA would mean an entity for a
// join table plus a lazy walk per scene. The write is a batch of two-column rows.
//
// **The citation shape is chat's, deliberately reused rather than reinvented.** The player's
// source rail and the click-through to the PDF page are Phase 6.2 and 11.2's components, and they
// take `Citation`. A video-specific citation record would have been a second thing to keep in step
// with the viewer, and the first divergence would have been a video citation that could not open.
@Repository
@RequiredArgsConstructor
public class VideoCitationRepository {

    private final JdbcTemplate jdbc;

    // Chunk ids in the order the scene lists them. Ignores duplicates within a scene — the model
    // is perfectly capable of grounding two sentences of one scene on the same chunk, and the
    // primary key (scene_id, chunk_id) says that is one citation, not two.
    public void save(UUID sceneId, List<UUID> chunkIds) {
        List<UUID> distinct = chunkIds.stream().distinct().toList();
        List<Object[]> batch = new ArrayList<>(distinct.size());
        for (int i = 0; i < distinct.size(); i++) {
            batch.add(new Object[]{sceneId, distinct.get(i), i + 1});
        }
        if (batch.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                insert into video_scene_citations (scene_id, chunk_id, ordinal)
                values (?, ?, ?)
                on conflict do nothing
                """, batch);
    }

    // Every scene's citations for one job, keyed by scene id. One query for the whole job rather
    // than one per scene: six scenes is six round trips to Supabase for a page that is polled
    // every two seconds while a render runs.
    //
    // A chunk whose document was deleted simply is not here — the foreign key removed the row —
    // which is the behaviour a citation rail wants: a source that no longer exists should vanish,
    // not render as a dead link.
    public Map<UUID, List<Citation>> findByJob(UUID jobId) {
        Map<UUID, List<Citation>> bySceneId = new HashMap<>();
        jdbc.query("""
                select vsc.scene_id, vsc.ordinal, c.id as chunk_id, c.document_id, c.page_number,
                       c.content, c.modality, d.filename, d.source
                from video_scene_citations vsc
                join video_scenes s on s.id = vsc.scene_id
                join document_chunks c on c.id = vsc.chunk_id
                join documents d on d.id = c.document_id
                where s.job_id = ?
                order by s.scene_index, vsc.ordinal
                """, rs -> {
            UUID sceneId = UUID.fromString(rs.getString("scene_id"));
            String content = rs.getString("content");
            Citation citation = new Citation(
                    rs.getInt("ordinal"),
                    UUID.fromString(rs.getString("chunk_id")),
                    UUID.fromString(rs.getString("document_id")),
                    rs.getString("filename"),
                    DocumentSource.valueOf(rs.getString("source")),
                    (Integer) rs.getObject("page_number"),
                    "VISUAL".equals(rs.getString("modality")),
                    snippet(content));
            bySceneId.computeIfAbsent(sceneId, key -> new ArrayList<>()).add(citation);
        }, jobId);
        return bySceneId;
    }

    // Citation.from does this for a RetrievedChunk; this row is not one, and duplicating four
    // lines is cheaper than inventing a RetrievedChunk from a join to call one static method on.
    private static String snippet(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.strip();
        return trimmed.length() <= 240 ? trimmed : trimmed.substring(0, 240).stripTrailing() + "…";
    }
}
