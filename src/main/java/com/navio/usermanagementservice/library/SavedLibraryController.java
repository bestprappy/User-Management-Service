package com.navio.usermanagementservice.library;

import static com.navio.usermanagementservice.library.LibraryContracts.*;
import com.navio.usermanagementservice.security.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import java.time.Instant;
import java.util.*;

@RestController @RequestMapping("/v1/users/me/saved") @RequiredArgsConstructor @Validated
public class SavedLibraryController {
    private final SavedLibraryService service;
    private <T> ResponseEntity<T> ok(T body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
    @GetMapping public ResponseEntity<Results> list(@CurrentUser AuthenticatedUser u,
        @RequestParam(defaultValue="ALL") @Pattern(regexp="ALL|PLAN|PLACE|TRASH|OPENED") String tab,
        @RequestParam(defaultValue="") @Size(max=200) String search,
        @RequestParam(defaultValue="NEWEST") @Pattern(regexp="NEWEST|OLDEST|NAME|OPENED") String sort,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="24") int size,
        @RequestParam(required=false) @Size(max=120) String country,@RequestParam(required=false) @Size(max=120) String city,
        @RequestParam(required=false) @Pattern(regexp="DIRECT|SHARED_PLAN") String source,@RequestParam(required=false) @Size(max=120) String category,
        @RequestParam(required=false) Instant savedAfter,@RequestParam(required=false) Instant openedAfter) {
        return ok(service.list(u.id(),new Query(tab,search,sort,page,size,country,city,source,category,savedAfter,openedAfter)));
    }
    @PostMapping("/states") public ResponseEntity<List<State>> states(@CurrentUser AuthenticatedUser u,@Valid @RequestBody StateRequest r){return ok(service.states(u.id(),r.identities()));}
    @PostMapping("/places") public ResponseEntity<Item> place(@CurrentUser AuthenticatedUser u,@Valid @RequestBody PlaceRequest r){return ok(service.savePlace(u.id(),r));}
    @PostMapping("/plans") public ResponseEntity<Item> plan(@CurrentUser AuthenticatedUser u,@Valid @RequestBody PlanRequest r){return ok(service.savePlan(u.id(),r.token()));}
    @PostMapping("/{id}/open") public ResponseEntity<Item> open(@CurrentUser AuthenticatedUser u,@PathVariable UUID id){return ok(service.open(u.id(),id));}
    @PostMapping("/{id}/restore") public ResponseEntity<Item> restore(@CurrentUser AuthenticatedUser u,@PathVariable UUID id){return ok(service.restore(u.id(),id));}
    @DeleteMapping("/{id}") public ResponseEntity<Void> remove(@CurrentUser AuthenticatedUser u,@PathVariable UUID id){service.remove(u.id(),id);return ok(null);}
    @DeleteMapping("/{id}/permanent") public ResponseEntity<Void> purge(@CurrentUser AuthenticatedUser u,@PathVariable UUID id){service.purge(u.id(),id);return ok(null);}
    @GetMapping("/{id}/sources/{token}") public ResponseEntity<PlanReference> source(@CurrentUser AuthenticatedUser u,@PathVariable UUID id,@PathVariable String token){return ok(service.source(u.id(),id,token));}
    @GetMapping("/{id}/reference") public ResponseEntity<PlanReference> reference(@CurrentUser AuthenticatedUser u,@PathVariable UUID id){return ok(service.reference(u.id(),id));}
}
