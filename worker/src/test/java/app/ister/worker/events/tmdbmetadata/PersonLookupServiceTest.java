package app.ister.worker.events.tmdbmetadata;

import app.ister.core.entity.PersonEntity;
import app.ister.core.repository.ImageRepository;
import app.ister.core.repository.PersonRepository;
import app.ister.tmdbapi.model.PersonDetails200Response;
import app.ister.worker.clients.TmdbClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonLookupServiceTest {

    @Mock
    private PersonRepository personRepository;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private ImageDownloadService imageDownloadService;
    @Mock
    private TmdbClient tmdbClient;
    @Mock
    private PlatformTransactionManager transactionManager;

    private PersonLookupService subject;

    @BeforeEach
    void setUp() {
        subject = new PersonLookupService(personRepository, imageRepository, imageDownloadService, tmdbClient, transactionManager);
    }

    @Test
    void foundByTmdbIdSkipsPersonDetailsCall() {
        PersonEntity existing = PersonEntity.builder().name("Keanu Reeves").tmdbId(6384L).build();
        when(personRepository.findByTmdbId(6384L)).thenReturn(Optional.of(existing));

        PersonEntity result = subject.getOrCreateFromTmdb(6384L, "Keanu Reeves", null);

        assertEquals(existing, result);
        verify(tmdbClient, never())._personDetails(anyInt(), any(), anyString());
        verify(personRepository, never()).saveAndFlush(any());
    }

    @Test
    void matchOnNameAndBirthYearEnrichesWithTmdbId() {
        PersonEntity existing = PersonEntity.builder().name("Keanu Reeves").birthYear(1964).build();
        when(personRepository.findByTmdbId(6384L)).thenReturn(Optional.empty());
        when(tmdbClient._personDetails(6384, null, "en-US"))
                .thenReturn(ResponseEntity.ok(new PersonDetails200Response().birthday("1964-09-02")));
        when(personRepository.findByNameAndBirthYear("Keanu Reeves", 1964)).thenReturn(List.of(existing));
        when(personRepository.saveAndFlush(existing)).thenReturn(existing);

        PersonEntity result = subject.getOrCreateFromTmdb(6384L, "Keanu Reeves", null);

        assertEquals(existing, result);
        assertEquals(6384L, result.getTmdbId());
        verify(personRepository).saveAndFlush(existing);
    }

    @Test
    void claimsPersonWithUnknownBirthYear() {
        PersonEntity musicArtist = PersonEntity.builder().name("Lady Gaga").build();
        when(personRepository.findByTmdbId(90633L)).thenReturn(Optional.empty());
        when(tmdbClient._personDetails(90633, null, "en-US"))
                .thenReturn(ResponseEntity.ok(new PersonDetails200Response().birthday("1986-03-28")));
        when(personRepository.findByNameAndBirthYear("Lady Gaga", 1986)).thenReturn(List.of());
        when(personRepository.findByNameAndBirthYearIsNull("Lady Gaga")).thenReturn(List.of(musicArtist));
        when(personRepository.saveAndFlush(musicArtist)).thenReturn(musicArtist);

        PersonEntity result = subject.getOrCreateFromTmdb(90633L, "Lady Gaga", null);

        assertEquals(musicArtist, result);
        assertEquals(90633L, result.getTmdbId());
        assertEquals(1986, result.getBirthYear());
    }

    @Test
    void createsNewPersonWhenNoMatch() {
        when(personRepository.findByTmdbId(1L)).thenReturn(Optional.empty());
        when(tmdbClient._personDetails(1, null, "en-US"))
                .thenReturn(ResponseEntity.ok(new PersonDetails200Response().birthday("1970-01-01")));
        when(personRepository.findByNameAndBirthYear("New Actor", 1970)).thenReturn(List.of());
        when(personRepository.findByNameAndBirthYearIsNull("New Actor")).thenReturn(List.of());
        when(personRepository.saveAndFlush(any(PersonEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        PersonEntity result = subject.getOrCreateFromTmdb(1L, "New Actor", null);

        assertEquals("New Actor", result.getName());
        assertEquals(1L, result.getTmdbId());
        assertEquals(1970, result.getBirthYear());
    }

    @Test
    void handlesMissingBirthday() {
        when(personRepository.findByTmdbId(1L)).thenReturn(Optional.empty());
        when(tmdbClient._personDetails(1, null, "en-US"))
                .thenReturn(ResponseEntity.ok(new PersonDetails200Response()));
        when(personRepository.findByNameAndBirthYearIsNull("No Birthday")).thenReturn(List.of());
        when(personRepository.saveAndFlush(any(PersonEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        PersonEntity result = subject.getOrCreateFromTmdb(1L, "No Birthday", null);

        assertEquals("No Birthday", result.getName());
        assertEquals(null, result.getBirthYear());
        verify(personRepository, never()).findByNameAndBirthYear(anyString(), anyInt());
    }

    @Test
    void concurrentCreationFallsBackToWinner() {
        PersonEntity winner = PersonEntity.builder().name("Racy Actor").tmdbId(7L).build();
        when(personRepository.findByTmdbId(7L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(tmdbClient._personDetails(7, null, "en-US"))
                .thenReturn(ResponseEntity.ok(new PersonDetails200Response().birthday("1980-01-01")));
        when(personRepository.findByNameAndBirthYear("Racy Actor", 1980)).thenReturn(List.of());
        when(personRepository.findByNameAndBirthYearIsNull("Racy Actor")).thenReturn(List.of());
        when(personRepository.saveAndFlush(any(PersonEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate tmdb_id"));

        PersonEntity result = subject.getOrCreateFromTmdb(7L, "Racy Actor", null);

        assertEquals(winner, result);
    }

    @Test
    void downloadsProfileImageWhenMissing() throws Exception {
        PersonEntity existing = PersonEntity.builder().name("Keanu Reeves").tmdbId(6384L).build();
        when(personRepository.findByTmdbId(6384L)).thenReturn(Optional.of(existing));
        when(imageRepository.findByPersonEntityId(existing.getId())).thenReturn(List.of());

        subject.getOrCreateFromTmdb(6384L, "Keanu Reeves", "/profile.jpg");

        verify(imageDownloadService).downloadAndSave(
                org.mockito.ArgumentMatchers.eq("https://image.tmdb.org/t/p/original/profile.jpg"),
                any(), anyString(), anyString(), any(ImageSave.MediaEntityRef.class));
    }
}
