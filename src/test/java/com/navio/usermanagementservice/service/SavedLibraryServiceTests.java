package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.library.*;
import com.navio.usermanagementservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SavedLibraryServiceTests {
    final SavedLibraryRepository repo=mock(SavedLibraryRepository.class);
    final UserRepository users=mock(UserRepository.class);
    final PublishedPlanClient plans=mock(PublishedPlanClient.class);
    final UUID owner=UUID.randomUUID();
    final SavedLibraryService service=new SavedLibraryService(repo,users,plans);

    @Test void canonicalGoogleIdentityMatchesBothEntryPoints() {
        assertThat(SavedLibraryService.placeKey("GOOGLE:ChIJabc",1.0,2.0))
            .isEqualTo(SavedLibraryService.placeKey("ChIJabc",1.0,2.0));
        assertThat(SavedLibraryService.placeKey(null,1.0,2.0)).isNotEqualTo(SavedLibraryService.placeKey(null,1.0,2.1));
    }
    @Test void searchNormalizationHandlesAccentsAndCase() {
        assertThat(SavedLibraryService.normalize("  CAFÉ   Tokyo ")).isEqualTo("cafe tokyo");
    }
    @Test void bookmarkNeverWritesPersonalAnchorOrDefault() {
        when(repo.save(eq(owner),any(),any(),any())).thenAnswer(i->i.getArgument(1));
        service.savePlace(owner,new LibraryContracts.PlaceRequest("GOOGLE:ChIJabc","Temple","Tokyo",1.0,2.0,"Japan","Tokyo","Temple",null));
        verify(users).lockSavedPlaces(owner);
        verify(repo).save(eq(owner),argThat(item->item.type().equals("PLACE")&&item.identity().equals("google:ChIJabc")),isNull(),any());
        verifyNoInteractions(plans);
    }
    @Test void sourceMetadataComesFromValidatedPublication() {
        var request=new LibraryContracts.PlaceRequest("ChIJabc","Temple","Tokyo",1.0,2.0,null,null,null,"token");
        when(plans.require("token")).thenThrow(new IllegalArgumentException("unavailable"));
        assertThatThrownBy(()->service.savePlace(owner,request)).hasMessage("unavailable");
        verify(repo,never()).save(any(),any(),any(),any());
    }
    @Test void plansUseVerifiedMetadataRatherThanCallerTitle() {
        var plan=new LibraryContracts.PlanReference("token","Verified title","Author","Paris","France",3,null);
        when(plans.require("token")).thenReturn(plan);
        when(repo.save(eq(owner),any(),any(),any())).thenAnswer(i->i.getArgument(1));
        assertThat(service.savePlan(owner,"token").name()).isEqualTo("Verified title");
        verify(repo).save(eq(owner),any(),isNull(),eq(50));
    }
    @Test void pageSizeIsBoundedAndQueryAlwaysHasAccountScope() {
        service.list(owner,new LibraryContracts.Query("ALL","","NAME",-4,999,null,null,null,null,null,null));
        verify(repo).list(eq(owner),argThat(q->q.page()==0&&q.size()==48));
    }
    @Test void mutationsResolveOwnershipInsideRepository() {
        var id=UUID.randomUUID();service.remove(owner,id); service.restore(owner,id);service.purge(owner,id);
        verify(repo).remove(owner,id);verify(repo).restore(owner,id,50);verify(repo).purge(owner,id);
    }
}
