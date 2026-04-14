/*
 * Copyright 2024 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.api.replay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.traccar.model.Position;
import org.traccar.model.ReplaySession;
import org.traccar.storage.Storage;
import org.traccar.storage.StorageException;
import org.traccar.storage.localCache.RedisCache;
import org.traccar.storage.query.Columns;
import org.traccar.storage.query.Condition;
import org.traccar.storage.query.Order;
import org.traccar.storage.query.Request;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@Singleton
public class ReplaySessionService {

    private static final int SESSION_TTL_SECONDS = 3600;
    private static final String KEY_PREFIX = "replay:session:";

    private final RedisCache redisCache;
    private final Storage storage;
    private final ObjectMapper objectMapper;

    @Inject
    public ReplaySessionService(RedisCache redisCache, Storage storage, ObjectMapper objectMapper) {
        this.redisCache = redisCache;
        this.storage = storage;
        this.objectMapper = objectMapper;
    }


    public ReplaySession createSession(long deviceId, Date from, Date to) throws StorageException {
        List<Position> countResult = storage.getObjects(Position.class, new Request(
                new Columns.Include("id"),
                new Condition.And(
                        new Condition.Equals("deviceId", deviceId),
                        new Condition.Between("fixTime", "from", from, "to", to))));

        ReplaySession session = new ReplaySession();
        session.setId(UUID.randomUUID().toString());
        session.setDeviceId(deviceId);
        session.setFrom(from.getTime());
        session.setTo(to.getTime());
        session.setTotalCount(countResult.size());
        session.setCreatedAt(System.currentTimeMillis());

        persistSession(session);
        return session;
    }


    public List<Position> getChunk(String sessionId, int offset, int limit) throws StorageException {
        ReplaySession session = getSession(sessionId);
        if (session == null) {
            return null;
        }

        refreshTtl(sessionId);

        Date from = new Date(session.getFrom());
        Date to = new Date(session.getTo());

        return storage.getObjects(Position.class, new Request(
                new Columns.All(),
                new Condition.And(
                        new Condition.Equals("deviceId", session.getDeviceId()),
                        new Condition.Between("fixTime", "from", from, "to", to)),
                new Order("fixTime", false, limit, offset)));
    }

    public ReplaySession getSession(String sessionId) {
        String json = redisCache.get(KEY_PREFIX + sessionId);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ReplaySession.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void persistSession(ReplaySession session) throws StorageException {
        try {
            String json = objectMapper.writeValueAsString(session);
            redisCache.setWithTTL(KEY_PREFIX + session.getId(), json, SESSION_TTL_SECONDS);
        } catch (JsonProcessingException e) {
            throw new StorageException(e);
        }
    }

    private void refreshTtl(String sessionId) {
        String key = KEY_PREFIX + sessionId;
        String value = redisCache.get(key);
        if (value != null) {
            redisCache.setWithTTL(key, value, SESSION_TTL_SECONDS);
        }
    }

}
