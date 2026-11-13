package com.navio.usermanagementservice.service;

import com.navio.usermanagementservice.dto.GuideFeedback.*;
import com.navio.usermanagementservice.repository.GuideFeedbackRepository;
import com.navio.usermanagementservice.repository.GuideFeedbackRepository.Publication;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class GuideFeedbackService {
    private final GuideFeedbackRepository repository;
    private Publication require(String token,boolean lock) {
        if(token==null||token.isBlank())throw new com.navio.usermanagementservice.exception.UserManagementExceptions.GuideNotFoundException();
        return repository.publication(token,lock)
            .orElseThrow(com.navio.usermanagementservice.exception.UserManagementExceptions.GuideNotFoundException::new);
    }
    public Summary summary(String token,UUID user) { return summary(token,user,false); }
    public Summary summary(String token,UUID user,boolean moderator) {
        Publication p=require(token,false);
        return new Summary(p.ratingCount()==0?null:(double)p.ratingSum()/p.ratingCount(),p.ratingCount(),p.commentCount(),
            repository.rating(p.id(),user),user!=null&&Objects.equals(user,p.owner()),repository.totalViews(token),repository.uniqueViews(token),user!=null&&moderator);
    }
    @Transactional public Summary rate(String token,UUID user,Integer score) {
        authenticated(user);Publication p=require(token,true);
        if(Objects.equals(p.owner(),user))throw new FeedbackException(403,"Publishers cannot rate their own guides.");
        if(score!=null&&(score<1||score>5))throw new FeedbackException(400,"Choose a rating between 1 and 5.");
        repository.rate(p.id(),user,score,repository.rating(p.id(),user));return summary(token,user);
    }
    public Comments comments(String token,UUID user,UUID parent,int page) { return comments(token,user,parent,page,false); }
    public Comments comments(String token,UUID user,UUID parent,int page,boolean moderator) {
        Publication p=require(token,false);
        if(parent!=null)root(p.id(),parent,false);
        return repository.comments(p.id(),user,user!=null&&(moderator||Objects.equals(user,p.owner())),parent,Math.clamp(page,0,10000));
    }
    @Transactional public void add(String token,UUID user,CommentRequest request) {
        authenticated(user);Publication p=require(token,true);
        if(request.parentId()!=null)root(p.id(),request.parentId(),true);
        String name=request.authorName()==null?"Navio traveler":request.authorName().replaceAll("[\\p{Cntrl}<>]","").strip();
        if(name.isBlank())name="Navio traveler";
        repository.insert(p.id(),user,name.substring(0,Math.min(name.length(),100)),text(request.content()),request.parentId());
    }
    @Transactional public void edit(String token,UUID user,UUID id,EditRequest request) {
        authenticated(user);Publication p=require(token,true);var c=comment(p.id(),id);
        if(!c.user().equals(user))throw new FeedbackException(403,"Only the author can edit this comment.");
        if(c.deleted())throw new FeedbackException(404,"This comment is no longer available.");
        if(!Objects.equals(request.version(),c.version()))throw new FeedbackException(409,"This comment changed. Reload it before editing.");
        repository.edit(p.id(),id,text(request.content()));
    }
    @Transactional public void delete(String token,UUID user,UUID id) { delete(token,user,id,false); }
    @Transactional public void delete(String token,UUID user,UUID id,boolean moderator) {
        authenticated(user);Publication p=require(token,true);var c=comment(p.id(),id);
        if(!moderator&&!c.user().equals(user)&&!Objects.equals(p.owner(),user))throw new FeedbackException(403,"You cannot remove this comment.");
        if(!c.deleted())repository.delete(p.id(),id);
    }
    @Transactional public void report(String token,UUID user,UUID id,ReportRequest request) {
        authenticated(user);Publication p=require(token,true);var c=comment(p.id(),id);
        if(c.deleted())throw new FeedbackException(404,"This comment is no longer available.");
        if(c.user().equals(user))throw new FeedbackException(400,"You cannot report your own comment.");
        repository.report(id,user,Objects.requireNonNull(request.reason()));
    }
    public List<Report> reports(String token,UUID user) { return reports(token,user,false); }
    public List<Report> reports(String token,UUID user,boolean moderator) {
        authenticated(user);Publication p=require(token,false);
        if(!moderator&&!Objects.equals(p.owner(),user))throw new FeedbackException(403,"Only the publisher or a moderator can review reports.");
        return repository.reports(p.id());
    }
    private GuideFeedbackRepository.Stored comment(UUID p,UUID id) {
        return repository.comment(p,id).orElseThrow(()->new FeedbackException(404,"This comment is no longer available."));
    }
    private void root(UUID p,UUID id,boolean writing) {
        var c=comment(p,id);
        if(c.parent()!=null)throw new FeedbackException(400,"Reply to the original comment instead.");
        if(writing&&c.deleted())throw new FeedbackException(409,"This conversation is closed to new replies.");
    }
    private void authenticated(UUID user) { if(user==null)throw new FeedbackException(401,"Sign in to contribute."); }
    private String text(String value) {
        if(value==null||value.isBlank()||value.length()>1500)throw new FeedbackException(400,"Comments must contain 1-1500 characters.");
        return value.strip();
    }
    public static class FeedbackException extends RuntimeException {
        private final int status;
        public FeedbackException(int status,String message){super(message);this.status=status;}
        public int status(){return status;}
    }
}
