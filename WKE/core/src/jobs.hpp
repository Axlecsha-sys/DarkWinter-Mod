// Bounded DAG executor. Work is outside the lock; publication is serialized with cancellation.
#pragma once
#include "../include/dw_core.h"
#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <vector>

namespace dw {
struct Outcome {
    int result=DW_OK;
    std::function<int()> publish;
};
class Jobs {
public:
    using Work=std::function<Outcome(const std::atomic<bool>&)>;
    struct Metrics { uint64_t workers,queued,running,handles,peak; };
private:
    struct Job {
        uint64_t id=0,parent=0;
        int state=0,result=DW_PENDING; // 0 waiting,1 ready,2 running,3 terminal
        std::atomic<bool> cancel{false};
        Work work;
        std::vector<uint64_t> children;
    };
    mutable std::mutex mutex_;
    std::condition_variable wake_;
    std::map<uint64_t,std::shared_ptr<Job>> jobs_;
    std::deque<std::shared_ptr<Job>> ready_;
    std::vector<std::thread> workers_;
    std::vector<std::pair<std::shared_ptr<Job>,int>> completion_;
    uint64_t next_=1,queued_=0,running_=0,peak_=0;
    uint32_t capacity_;
    bool stopping_=false;

    void finish_locked(const std::shared_ptr<Job>& job,int result) {
        completion_.clear();completion_.emplace_back(job,result);
        size_t cursor=0;bool became_ready=false;
        while(cursor<completion_.size()) {
            auto current=std::move(completion_[cursor++]);
            auto& j=current.first;
            if(j->state==3) continue;
            if(j->state==2) --running_; else --queued_;
            if(j->state==1) ready_.erase(std::remove(ready_.begin(),ready_.end(),j),ready_.end());
            if(j->parent) {
                auto p=jobs_.find(j->parent);
                if(p!=jobs_.end()) {
                    auto& list=p->second->children;
                    list.erase(std::remove(list.begin(),list.end(),j->id),list.end());
                }
                j->parent=0;
            }
            j->state=3;j->result=current.second;j->work={};
            auto children=std::move(j->children);
            for(auto id:children) {
                auto found=jobs_.find(id);
                if(found==jobs_.end()||found->second->state==3) continue;
                auto& child=found->second;child->parent=0;
                if(current.second==DW_OK&&!stopping_) {
                    child->state=1;ready_.push_back(child);became_ready=true;
                } else completion_.emplace_back(child,DW_DEPENDENCY);
            }
        }
        completion_.clear();
        if(became_ready)wake_.notify_all();
    }
    void worker() {
        for(;;) {
            std::shared_ptr<Job> job;
            {
                std::unique_lock lock(mutex_);
                wake_.wait(lock,[&]{return stopping_||!ready_.empty();});
                if(stopping_) return;
                job=ready_.front();ready_.pop_front();
                --queued_;++running_;peak_=std::max(peak_,running_);job->state=2;
            }
            Outcome output;
            try {output=job->work(job->cancel);}
            catch(...) {output={DW_INTERNAL,{}};}
            {
                std::lock_guard lock(mutex_);
                int result=job->cancel.load(std::memory_order_relaxed)?DW_CANCELLED:output.result;
                if(result==DW_OK&&output.publish) {
                    try {result=output.publish();} catch(...) {result=DW_INTERNAL;}
                }
                // No successful cancel can race past the publication above.
                finish_locked(job,result);
            }
        }
    }
public:
    Jobs(uint32_t workers,uint32_t capacity):capacity_(capacity) {
        if(workers<1||workers>32||capacity<1||capacity>4096) throw std::invalid_argument("job limits");
        completion_.reserve(size_t(capacity)+workers);
        try {
            for(uint32_t i=0;i<workers;++i) workers_.emplace_back([this]{worker();});
        } catch(...) {stop();throw;}
    }
    ~Jobs(){stop();}
    Jobs(const Jobs&)=delete;
    Jobs& operator=(const Jobs&)=delete;
    void stop() {
        {
            std::lock_guard lock(mutex_);
            if(stopping_) return;
            stopping_=true;
            for(auto& pair:jobs_) pair.second->cancel.store(true,std::memory_order_relaxed);
            // Running jobs retain their work until their worker returns.
            std::vector<std::shared_ptr<Job>> waiting;
            for(auto& pair:jobs_) if(pair.second->state<2) waiting.push_back(pair.second);
            for(auto& job:waiting) finish_locked(job,DW_CANCELLED);
        }
        wake_.notify_all();
        for(auto& worker:workers_) if(worker.joinable()) worker.join();
    }
    int submit(Work work,uint64_t dependency,uint64_t& ticket) {
        std::lock_guard lock(mutex_);
        if(stopping_) return DW_CANCELLED;
        if(queued_>=capacity_||jobs_.size()>=capacity_+workers_.size()) return DW_BUSY;
        std::shared_ptr<Job> parent;
        if(dependency) {
            auto found=jobs_.find(dependency);
            if(found==jobs_.end()) return DW_NOT_FOUND;
            parent=found->second;
        }
        if(next_==0) return DW_LIMIT;
        auto job=std::make_shared<Job>();job->id=next_++;job->work=std::move(work);
        jobs_.emplace(job->id,job);++queued_;ticket=job->id;
        if(parent&&parent->state!=3) {
            job->parent=parent->id;parent->children.push_back(job->id);
        } else if(parent&&parent->result!=DW_OK) finish_locked(job,DW_DEPENDENCY);
        else {job->state=1;ready_.push_back(job);wake_.notify_one();}
        return DW_OK;
    }
    int poll(uint64_t ticket) const {
        std::lock_guard lock(mutex_);
        auto found=jobs_.find(ticket);return found==jobs_.end()?DW_NOT_FOUND:found->second->result;
    }
    int cancel(uint64_t ticket) {
        std::lock_guard lock(mutex_);
        auto found=jobs_.find(ticket);if(found==jobs_.end()) return DW_NOT_FOUND;
        auto job=found->second;
        if(job->state==3) return DW_BUSY;
        job->cancel.store(true,std::memory_order_relaxed);
        if(job->state<2) finish_locked(job,DW_CANCELLED);
        return DW_OK;
    }
    int release(uint64_t ticket) {
        std::lock_guard lock(mutex_);
        auto found=jobs_.find(ticket);if(found==jobs_.end()) return DW_NOT_FOUND;
        if(found->second->state!=3) return DW_BUSY;
        jobs_.erase(found);return DW_OK;
    }
    Metrics metrics() const {
        std::lock_guard lock(mutex_);
        return {workers_.size(),queued_,running_,jobs_.size(),peak_};
    }
};
}
