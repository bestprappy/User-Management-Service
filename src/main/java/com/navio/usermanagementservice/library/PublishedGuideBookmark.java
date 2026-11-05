package com.navio.usermanagementservice.library;
import com.navio.usermanagementservice.service.*;
import com.navio.usermanagementservice.dto.GuideDtos.Fields;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** Resolves public guide data through its owning service, never a client-provided title or draft. */
@Service @RequiredArgsConstructor
public class PublishedGuideBookmark {
 private final GuideService curated;
 private final UserGuideService travelers;
 public LibraryContracts.Item resolve(String source,UUID id){
  Fields fields;String author=null;
  if("TRAVELER".equals(source)){var published=travelers.publishedDetail(id);fields=published.guide();author=published.author()==null?null:published.author().displayName();}
  else if("CURATED".equals(source))fields=curated.publishedDetail(id).guide();
  else throw new IllegalArgumentException("Invalid guide source");
  String city=fields.destination().name();if(city.length()>120)city=city.substring(0,120);
  return new LibraryContracts.Item(UUID.randomUUID(),"GUIDE","guide:"+source.toLowerCase(Locale.ROOT)+":"+id,fields.name(),null,fields.coverPlaceId(),null,null,fields.country(),city,source,author,null,null,null,null,List.of());
 }
 public void validate(LibraryContracts.Item item){
  var parts=item.identity().split(":",3);resolve(parts[1].toUpperCase(Locale.ROOT),UUID.fromString(parts[2]));
 }
}
