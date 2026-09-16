package app.ister.worker.events.tmdbmetadata;

import app.ister.tmdbapi.model.SearchMovie200ResponseResultsInner;
import app.ister.tmdbapi.model.SearchTv200ResponseResultsInner;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TmdbResultSelectorTest {

    private final TmdbResultSelector subject = new TmdbResultSelector();

    private SearchTv200ResponseResultsInner tv(int id, String name, double popularity) {
        SearchTv200ResponseResultsInner r = new SearchTv200ResponseResultsInner();
        r.setId(id);
        r.setName(name);
        r.setPopularity(BigDecimal.valueOf(popularity));
        return r;
    }

    private SearchMovie200ResponseResultsInner movie(int id, String title, double popularity) {
        SearchMovie200ResponseResultsInner r = new SearchMovie200ResponseResultsInner();
        r.setId(id);
        r.setTitle(title);
        r.setPopularity(BigDecimal.valueOf(popularity));
        return r;
    }

    @Test
    void emptyListYieldsEmpty() {
        assertThat(subject.selectTv(List.of(), "anything")).isEmpty();
        assertThat(subject.selectTv(null, "anything")).isEmpty();
    }

    @Test
    void singleResultIsReturnedRegardlessOfName() {
        SearchTv200ResponseResultsInner only = tv(99, "Something Completely Different", 0.1);
        assertThat(subject.selectTv(List.of(only), "query"))
                .hasValueSatisfying(r -> assertThat(r.getId()).isEqualTo(99));
    }

    @Test
    void prefersNormalizedExactNameMatchOverTmdbOrdering() {
        // Reproduces the real bug: TMDB returns the spin-off first for query "Fairly Odd Parents".
        SearchTv200ResponseResultsInner spinOff = tv(275816, "The Fairly OddParents Superhero Spectacle", 9.9);
        SearchTv200ResponseResultsInner realSeries = tv(4630, "The Fairly OddParents", 5.0);

        Optional<SearchTv200ResponseResultsInner> chosen =
                subject.selectTv(List.of(spinOff, realSeries), "Fairly Odd Parents");

        assertThat(chosen).hasValueSatisfying(r -> assertThat(r.getId()).isEqualTo(4630));
    }

    @Test
    void matchesOnOriginalNameToo() {
        SearchTv200ResponseResultsInner localized = tv(1, "Localised Title", 1.0);
        localized.setOriginalName("Fairly Odd Parents");
        SearchTv200ResponseResultsInner other = tv(2, "Unrelated", 8.0);

        assertThat(subject.selectTv(List.of(other, localized), "Fairly Odd Parents"))
                .hasValueSatisfying(r -> assertThat(r.getId()).isEqualTo(1));
    }

    @Test
    void fallsBackToMostPopularSimilarTitleWhenNoExactMatch() {
        SearchMovie200ResponseResultsInner unrelated = movie(1, "Rob-B-Hood", 9.0);
        SearchMovie200ResponseResultsInner similar = movie(2, "DuckTales: The Movie - Treasure of the Lost Lamp", 3.0);
        SearchMovie200ResponseResultsInner alsoSimilar = movie(3, "Treasure of the Lost Lamp: Making Of", 1.0);

        assertThat(subject.selectMovie(List.of(unrelated, similar, alsoSimilar), "The Movie Treasure of the Lost Lamp"))
                .hasValueSatisfying(r -> assertThat(r.getId()).isEqualTo(2));
    }

    /** A wrong directory year makes TMDB return random titles; those must not be picked by popularity. */
    @Test
    void rejectsUnrelatedResultsWhenNoExactMatch() {
        SearchTv200ResponseResultsInner a = tv(1, "Some Show", 3.0);
        SearchTv200ResponseResultsInner b = tv(2, "Another Show", 7.0);

        assertThat(subject.selectTv(List.of(a, b), "Totally Different")).isEmpty();
        assertThat(subject.selectMovie(List.of(movie(1, "Home Movies 300-1", 5.0), movie(2, "Video 3000", 4.0)), "300")).isEmpty();
    }

    @Test
    void exactSelectionIgnoresSimilarTitles() {
        SearchMovie200ResponseResultsInner similar = movie(1, "Kingsman: The Secret Service Revealed", 9.0);
        SearchMovie200ResponseResultsInner exact = movie(2, "Kingsman: The Secret Service", 8.0);

        assertThat(subject.selectMovieExact(List.of(similar, exact), "Kingsman: The Secret Service"))
                .hasValueSatisfying(r -> assertThat(r.getId()).isEqualTo(2));
        assertThat(subject.selectMovieExact(List.of(similar), "Kingsman: The Secret Service")).isEmpty();
    }

    @Test
    void nearYearSelectionKeepsOnlyExactTitlesReleasedCloseToTheYear() {
        SearchMovie200ResponseResultsInner original = movie(1, "Life Is Beautiful", 9.0);
        original.setReleaseDate("1997-12-20");
        SearchMovie200ResponseResultsInner remake = movie(2, "Life Is Beautiful", 3.0);
        remake.setReleaseDate("2013-05-01");
        SearchMovie200ResponseResultsInner undated = movie(3, "Life Is Beautiful", 30.0);

        assertThat(subject.selectMovieNearYear(List.of(original, remake, undated), "Life Is Beautiful", 1998))
                .hasValueSatisfying(r -> assertThat(r.getId()).isEqualTo(1));
        assertThat(subject.selectMovieNearYear(List.of(original, remake, undated), "Life Is Beautiful", 2005)).isEmpty();
        assertThat(TmdbResultSelector.nearYear("2007-03-07", 2006)).isTrue();
        assertThat(TmdbResultSelector.nearYear("1994-02-03", 2000)).isFalse();
        assertThat(TmdbResultSelector.nearYear("", 2000)).isFalse();
    }

    @Test
    void breaksExactMatchTiesByPopularity() {
        SearchTv200ResponseResultsInner low = tv(1, "The Office", 4.0);
        SearchTv200ResponseResultsInner high = tv(2, "The Office", 40.0);

        assertThat(subject.selectTv(List.of(low, high), "Office"))
                .hasValueSatisfying(r -> assertThat(r.getId()).isEqualTo(2));
    }

    @Test
    void movieSelectionUsesTitle() {
        SearchMovie200ResponseResultsInner spinOff = movie(1, "Batman Begins Behind The Scenes", 9.0);
        SearchMovie200ResponseResultsInner real = movie(2, "Batman Begins", 8.0);

        assertThat(subject.selectMovie(List.of(spinOff, real), "Batman Begins"))
                .hasValueSatisfying(r -> assertThat(r.getId()).isEqualTo(2));
    }

    @Test
    void normalizeCollapsesPunctuationSpacingAndLeadingThe() {
        assertThat(TmdbResultSelector.normalize("The Fairly OddParents"))
                .isEqualTo(TmdbResultSelector.normalize("Fairly Odd Parents"))
                .isEqualTo("fairlyoddparents");
        assertThat(TmdbResultSelector.normalize(null)).isEmpty();
    }
}
