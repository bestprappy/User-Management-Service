package com.navio.usermanagementservice.service;
import com.navio.usermanagementservice.library.*;
import com.navio.usermanagementservice.dto.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SavedGuideReferenceTests {
 @Test void referenceUsesPublishedServerMetadataAndSourceNamespace(){
  var travelers=mock(UserGuideService.class);var curated=mock(GuideService.class);
  var fields=mock(GuideDtos.Fields.class);var destination=mock(GuideDtos.Destination.class);var id=UUID.randomUUID();
  when(fields.name()).thenReturn("Tokyo gardens");when(fields.country()).thenReturn("Japan");when(fields.destination()).thenReturn(destination);when(destination.name()).thenReturn("Tokyo");when(fields.coverPlaceId()).thenReturn("ChIJcover");
  when(travelers.publishedDetail(id)).thenReturn(new UserGuideDtos.PublishedResponse(id,fields,null,null,null));
  var result=new PublishedGuideBookmark(curated,travelers).resolve("TRAVELER",id);
  assertEquals("guide:traveler:"+id,result.identity());assertEquals("GUIDE",result.type());assertEquals("Tokyo gardens",result.name());assertEquals("ChIJcover",result.providerPlaceId());
  verify(travelers).publishedDetail(id);verifyNoInteractions(curated);
 }
 @Test void cannotResolveArbitrarySourceOrUnpublishedGuide(){
  var travelers=mock(UserGuideService.class);var curated=mock(GuideService.class);var service=new PublishedGuideBookmark(curated,travelers);var id=UUID.randomUUID();
  assertThrows(IllegalArgumentException.class,()->service.resolve("EDITOR",id));
  when(travelers.publishedDetail(id)).thenThrow(new IllegalStateException("Unavailable"));
  assertThrows(IllegalStateException.class,()->service.resolve("TRAVELER",id));
 }
}
