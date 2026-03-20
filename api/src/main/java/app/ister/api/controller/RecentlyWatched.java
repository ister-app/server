package app.ister.api.controller;

import app.ister.core.entity.EpisodeEntity;
import app.ister.core.entity.MovieEntity;
import app.ister.core.enums.MediaType;

import java.time.Instant;

record RecentlyWatched(MediaType type, EpisodeEntity episode, MovieEntity movie, Instant lastWatched) {}
