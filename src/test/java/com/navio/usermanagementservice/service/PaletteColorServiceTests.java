package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.PaletteColorDtos.CreateRequest;
import com.navio.usermanagementservice.dto.PaletteColorDtos.UpdateTextColorRequest;
import com.navio.usermanagementservice.model.DeletedPaletteColor;
import com.navio.usermanagementservice.exception.UserManagementExceptions.*;
import com.navio.usermanagementservice.model.PaletteColor;
import com.navio.usermanagementservice.repository.DeletedPaletteColorRepository;
import com.navio.usermanagementservice.repository.PaletteColorRepository;
import com.navio.usermanagementservice.security.AuthenticatedUser;
import com.navio.usermanagementservice.security.NavioRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaletteColorServiceTests {
    static final AuthenticatedUser ADMIN = new AuthenticatedUser(UUID.fromString("00000000-0000-4000-8000-000000000001"), "a", "a@example.com", "Admin", Set.of(NavioRole.ADMIN));
    static final AuthenticatedUser MODERATOR = new AuthenticatedUser(UUID.fromString("00000000-0000-4000-8000-000000000002"), "m", "m@example.com", "Mod", Set.of(NavioRole.MODERATOR));

    @Mock PaletteColorRepository repository;
    @Mock DeletedPaletteColorRepository deletedRepository;
    @Mock AuditService audit;
    PaletteColorService service;

    @BeforeEach void setUp() {
        service = new PaletteColorService(repository, deletedRepository, audit);
        lenient().when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(repository.count()).thenReturn(24L);
        lenient().when(repository.countByRetiredAtIsNull()).thenReturn(24L);
        lenient().when(repository.maxSortOrder()).thenReturn(240);
    }

    static PaletteColor color(String id, String hex, boolean retired) {
        PaletteColor color = new PaletteColor();
        color.setId(id); color.setName(id); color.setHex(hex); color.setSortOrder(10);
        if (retired) color.setRetiredAt(Instant.parse("2026-10-01T00:00:00Z"));
        return color;
    }

    @Test void moderatorsCannotListAddEditRetireRestoreOrDeleteColors() {
        assertThatThrownBy(() -> service.adminList(MODERATOR)).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.create(MODERATOR, new CreateRequest("Sunset", "#E8590C"))).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.updateTextColor(MODERATOR, "teal", new UpdateTextColorRequest("black", "black"))).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.retire(MODERATOR, "teal")).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.restore(MODERATOR, "teal")).isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> service.delete(MODERATOR, "old")).isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(repository, deletedRepository, audit);
    }

    @Test void aNewColorGetsAReadableIdALowercaseHexTheLastPlaceAndAnAuditEntry() {
        var created = service.create(ADMIN, new CreateRequest("  Sunset   Orange ", "#E8590C"));
        assertThat(created.id()).isEqualTo("sunset-orange");
        assertThat(created.name()).isEqualTo("Sunset Orange");
        assertThat(created.hex()).isEqualTo("#e8590c");
        assertThat(created.sortOrder()).isEqualTo(250);
        assertThat(created.retired()).isFalse();
        assertThat(created.textOnLight()).isEqualTo("auto");
        assertThat(created.textOnDark()).isEqualTo("auto");
        verify(audit).record(eq(ADMIN.id()), eq("PALETTE_COLOR_CREATED"), eq("PALETTE_COLOR"), any(), isNull(), any(), eq(Map.of("colorId", "sunset-orange")));
    }

    @Test void aNameWhoseIdIsTakenGetsANumberedId() {
        when(repository.existsById("teal")).thenReturn(true);
        when(repository.existsById("teal-2")).thenReturn(true);
        assertThat(service.create(ADMIN, new CreateRequest("Teal!", "#118877")).id()).isEqualTo("teal-3");
    }

    @Test void aNewColorKeepsTheLabelColorsItWasCreatedWith() {
        var created = service.create(ADMIN, new CreateRequest("Sunset", "#E8590C", "black", "white"));
        assertThat(created.textOnLight()).isEqualTo("black");
        assertThat(created.textOnDark()).isEqualTo("white");
    }

    @Test void aDeletedColorsIdIsNeverGivenToANewColor() {
        when(deletedRepository.existsById("sunset")).thenReturn(true);
        assertThat(service.create(ADMIN, new CreateRequest("Sunset", "#E8590C")).id()).isEqualTo("sunset-2");
    }

    @Test void theLabelColorCanChangeForEachThemeAndIsAudited() {
        PaletteColor amber = color("amber", "#ea8a18", false);
        when(repository.findById("amber")).thenReturn(Optional.of(amber));
        var updated = service.updateTextColor(ADMIN, "amber", new UpdateTextColorRequest("black", "white"));
        assertThat(updated.textOnLight()).isEqualTo("black");
        assertThat(updated.textOnDark()).isEqualTo("white");
        assertThat(updated.hex()).isEqualTo("#ea8a18");
        verify(audit).record(eq(ADMIN.id()), eq("PALETTE_COLOR_TEXT_CHANGED"), eq("PALETTE_COLOR"), any(), any(), any(), eq(Map.of("colorId", "amber")));
    }

    @Test void settingTheSameLabelColorsChangesNothing() {
        when(repository.findById("amber")).thenReturn(Optional.of(color("amber", "#ea8a18", false)));
        service.updateTextColor(ADMIN, "amber", new UpdateTextColorRequest("auto", "auto"));
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test void aRetiredColorIsDeletedAndItsIdReserved() {
        PaletteColor old = color("old", "#123456", true);
        when(repository.findById("old")).thenReturn(Optional.of(old));
        service.delete(ADMIN, "old");
        verify(repository).delete(old);
        ArgumentCaptor<DeletedPaletteColor> reserved = ArgumentCaptor.forClass(DeletedPaletteColor.class);
        verify(deletedRepository).saveAndFlush(reserved.capture());
        assertThat(reserved.getValue().getId()).isEqualTo("old");
        assertThat(reserved.getValue().getHex()).isEqualTo("#123456");
        assertThat(reserved.getValue().getDeletedBy()).isEqualTo(ADMIN.id());
        verify(audit).record(eq(ADMIN.id()), eq("PALETTE_COLOR_DELETED"), eq("PALETTE_COLOR"), any(), any(), isNull(), eq(Map.of("colorId", "old")));
    }

    @Test void aColorStillInThePaletteMustBeRemovedBeforeItIsDeleted() {
        when(repository.findById("amber")).thenReturn(Optional.of(color("amber", "#ea8a18", false)));
        assertThatThrownBy(() -> service.delete(ADMIN, "amber")).isInstanceOf(BusinessRuleException.class)
                .hasMessage("Remove this color from the palette before deleting it");
        verify(repository, never()).delete(any());
        verifyNoInteractions(deletedRepository, audit);
    }

    @Test void theDefaultColorThatReplacesDeletedOnesCannotBeDeleted() {
        PaletteColor teal = color("teal", "#00ae84", true);
        teal.setName("Teal");
        when(repository.findById("teal")).thenReturn(Optional.of(teal));
        assertThatThrownBy(() -> service.delete(ADMIN, "teal")).isInstanceOf(BusinessRuleException.class)
                .hasMessage("Teal replaces deleted colors, so it cannot be deleted");
        verify(repository, never()).delete(any());
        verifyNoInteractions(deletedRepository, audit);
    }

    @Test void deletingAnUnknownColorIsNotFound() {
        when(repository.findById("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.delete(ADMIN, "missing")).isInstanceOf(PaletteColorNotFoundException.class);
        verifyNoInteractions(deletedRepository, audit);
    }

    @Test void aNameWithoutLeadingLetterStillGetsAValidId() {
        assertThat(service.create(ADMIN, new CreateRequest("2026 Brand", "#118877")).id()).isEqualTo("color-2026-brand");
        assertThat(service.create(ADMIN, new CreateRequest("สีส้ม", "#c2410c")).id()).isEqualTo("color");
    }

    @Test void aColorAlreadyInThePaletteIsAConflict() {
        when(repository.existsByHex("#00ae84")).thenReturn(true);
        assertThatThrownBy(() -> service.create(ADMIN, new CreateRequest("Mint", "#00AE84")))
                .isInstanceOf(PaletteColorConflictException.class).hasMessage("This color is already in the palette");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test void aNameAlreadyInThePaletteIsAConflict() {
        when(repository.existsByNameIgnoreCase("Teal")).thenReturn(true);
        assertThatThrownBy(() -> service.create(ADMIN, new CreateRequest("Teal", "#118877")))
                .isInstanceOf(PaletteColorConflictException.class).hasMessage("Another color already uses this name");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test void colorsThatDisappearOnTheLightOrDarkBackgroundAreRejected() {
        for (String hex : List.of("#ffffff", "#f5f5f5", "#000000", "#15161b")) {
            assertThatThrownBy(() -> service.create(ADMIN, new CreateRequest("Edge " + hex, hex))).isInstanceOf(BusinessRuleException.class);
        }
        verify(repository, never()).saveAndFlush(any());
    }

    @Test void everyBuiltInColorPassesTheSurfaceContrastRule() {
        for (String hex : List.of("#00ae84", "#00b2b2", "#008cdf", "#5d67ee", "#8654cc", "#d83e89", "#f0584b", "#ea8a18",
                "#009342", "#00603c", "#005189", "#3b4372", "#613888", "#972767", "#ac312a", "#a94700",
                "#56932b", "#677014", "#aa7e00", "#089ac3", "#b243ba", "#7c5336", "#766d62", "#4e535b")) {
            assertThat(PaletteColorService.contrast(hex, "#ffffff")).as(hex).isGreaterThanOrEqualTo(PaletteColorService.MIN_SURFACE_CONTRAST);
            assertThat(PaletteColorService.contrast(hex, "#1b1d24")).as(hex).isGreaterThanOrEqualTo(PaletteColorService.MIN_SURFACE_CONTRAST);
        }
    }

    @Test void aFullPaletteRefusesAnotherActiveColor() {
        when(repository.countByRetiredAtIsNull()).thenReturn((long) PaletteColorService.MAX_ACTIVE);
        assertThatThrownBy(() -> service.create(ADMIN, new CreateRequest("Sunset", "#e8590c"))).isInstanceOf(BusinessRuleException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test void theTableIsBoundedEvenCountingRetiredColors() {
        when(repository.count()).thenReturn((long) PaletteColorService.MAX_TOTAL);
        assertThatThrownBy(() -> service.create(ADMIN, new CreateRequest("Sunset", "#e8590c"))).isInstanceOf(BusinessRuleException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test void retiringHidesTheColorWithoutDeletingIt() {
        PaletteColor teal = color("teal", "#00ae84", false);
        when(repository.findById("teal")).thenReturn(Optional.of(teal));
        var retired = service.retire(ADMIN, "teal");
        assertThat(retired.retired()).isTrue();
        assertThat(retired.hex()).isEqualTo("#00ae84");
        verify(repository, never()).delete(any());
        verify(audit).record(eq(ADMIN.id()), eq("PALETTE_COLOR_RETIRED"), eq("PALETTE_COLOR"), any(), any(), any(), any());
    }

    @Test void theLastActiveColorCannotBeRetired() {
        when(repository.findById("teal")).thenReturn(Optional.of(color("teal", "#00ae84", false)));
        when(repository.countByRetiredAtIsNull()).thenReturn(1L);
        assertThatThrownBy(() -> service.retire(ADMIN, "teal")).isInstanceOf(BusinessRuleException.class).hasMessage("Keep at least one color in the palette");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test void retiringAnAlreadyRetiredColorChangesNothing() {
        when(repository.findById("teal")).thenReturn(Optional.of(color("teal", "#00ae84", true)));
        assertThat(service.retire(ADMIN, "teal").retired()).isTrue();
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test void restoringBringsARetiredColorBack() {
        when(repository.findById("teal")).thenReturn(Optional.of(color("teal", "#00ae84", true)));
        assertThat(service.restore(ADMIN, "teal").retired()).isFalse();
        ArgumentCaptor<PaletteColor> saved = ArgumentCaptor.forClass(PaletteColor.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getRetiredAt()).isNull();
    }

    @Test void restoringIntoAFullPaletteIsRefused() {
        when(repository.findById("teal")).thenReturn(Optional.of(color("teal", "#00ae84", true)));
        when(repository.countByRetiredAtIsNull()).thenReturn((long) PaletteColorService.MAX_ACTIVE);
        assertThatThrownBy(() -> service.restore(ADMIN, "teal")).isInstanceOf(BusinessRuleException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test void anUnknownColorIsNotFound() {
        when(repository.findById("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.retire(ADMIN, "missing")).isInstanceOf(PaletteColorNotFoundException.class);
    }

    @Test void readersGetRetiredColorsTooSoSavedTripsKeepTheirColors() {
        when(repository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of(color("teal", "#00ae84", false), color("old", "#123456", true)));
        assertThat(service.all()).extracting(c -> c.id() + ":" + c.retired()).containsExactly("teal:false", "old:true");
    }

    @Test void readersGetDeletedIdsInTheDefaultColorSoSavedTripsFallBack() {
        DeletedPaletteColor gone = new DeletedPaletteColor();
        gone.setId("red"); gone.setName("Red"); gone.setHex("#ff0000");
        when(repository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of(color("teal", "#00ae84", false)));
        when(deletedRepository.findAllByOrderByIdAsc()).thenReturn(List.of(gone));
        assertThat(service.all()).last().satisfies(red -> {
            assertThat(red.id()).isEqualTo("red");
            assertThat(red.deleted()).isTrue();
            assertThat(red.retired()).isTrue();
            assertThat(red.hex()).isEqualTo("#00ae84");
        });
    }

    @Test void documentsMayKeepRetiredColorsButNotInventedOnes() {
        when(repository.findAllById(Set.of("teal", "old"))).thenReturn(List.of(color("teal", "#00ae84", false), color("old", "#123456", true)));
        assertThatCode(() -> service.requireKnown(List.of("teal", "old", "teal"))).doesNotThrowAnyException();
        when(repository.findAllById(Set.of("teal", "invented"))).thenReturn(List.of(color("teal", "#00ae84", false)));
        assertThatThrownBy(() -> service.requireKnown(List.of("teal", "invented"))).isInstanceOf(BusinessRuleException.class);
    }

    @Test void documentsMayKeepADeletedColorTheyAlreadyUse() {
        DeletedPaletteColor gone = new DeletedPaletteColor();
        gone.setId("red");
        when(repository.findAllById(Set.of("teal", "red"))).thenReturn(List.of(color("teal", "#00ae84", false)));
        when(deletedRepository.findAllById(Set.of("teal", "red"))).thenReturn(List.of(gone));
        assertThatCode(() -> service.requireKnown(List.of("teal", "red"))).doesNotThrowAnyException();
    }
}
