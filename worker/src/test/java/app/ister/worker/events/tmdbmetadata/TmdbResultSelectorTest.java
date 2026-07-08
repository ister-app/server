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
    void fallsBackToMostPopularWhenNoExactMatch() {
        SearchTv200ResponseResultsInner a = tv(1, "Some Show", 3.0);
        SearchTv200ResponseResultsInner b = tv(2, "Another Show", 7.0);

        assertThat(subject.selectTv(List.of(a, b), "Totally Different"))
                .hasValueSatisfying(r -> assertThat(r.getId()).isEqualTo(2));
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
