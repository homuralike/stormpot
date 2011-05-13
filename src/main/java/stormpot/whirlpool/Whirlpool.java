/*
 * Copyright 2011 Chris Vest
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot.whirlpool;

import static java.util.concurrent.atomic.AtomicReferenceFieldUpdater.*;
import static java.util.concurrent.atomic.AtomicIntegerFieldUpdater.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import stormpot.Completion;
import stormpot.Config;
import stormpot.LifecycledPool;
import stormpot.Pool;
import stormpot.PoolException;
import stormpot.Poolable;
import stormpot.qpool.QueuePool;

/**
 * Whirlpool is a {@link Pool} that is based on a queue-like structure, made
 * concurrent using the Flat-Combining technique of Hendler, Incze, Shavit and
 * Tzafrir.
 * <p>
 * <strong>NOTE:</strong> This Pool implementation is still experimental.
 * Use {@link QueuePool} for an implementation that is currently considered
 * more reliable.
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 * @param <T> The type of {@link Poolable} managed by this pool.
 *
 */
public class Whirlpool<T extends Poolable> implements LifecycledPool<T> {
  static final int LOCKED = 1;
  static final int UNLOCKED = 0;
  static final int WAIT_SPINS = 128;
  static final int CLEANUP_MASK = (1 << 12) - 1;
  static final int PARK_TIME_NS = 1000000;
  static final int EXPIRE_PASS_COUNT = 100;

  static final WSlot CLAIM = new WSlot(null);
  static final WSlot RELIEVE = new WSlot(null);
  static final WSlot RELEASE = new WSlot(null);
  static final WSlot SHUTDOWN = new WSlot(null);
  static final WSlot TIMEOUT = new WSlot(null);
  
  static final AtomicReferenceFieldUpdater<Whirlpool, Request> publistCas =
    newUpdater(Whirlpool.class, Request.class, "publist");
  static final AtomicIntegerFieldUpdater<Whirlpool> lockCas =
    newUpdater(Whirlpool.class, "lock");
  
  private final RequestThreadLocal requestTL = new RequestThreadLocal();
  
  private volatile Request publist;
  @SuppressWarnings("unused")
  private volatile int lock = UNLOCKED;
  private volatile boolean shutdown = false;
  private int combiningPass;
  private WSlot liveStack;
  private WSlot deadStack;
  private long ttl;
  private WpAllocThread alloc;
  
  /**
   * Construct a new Whirlpool instance from the given {@link Config}.
   * @param config The pool configuration to use.
   */
  public Whirlpool(Config<T> config) {
    synchronized (config) {
      config.validate();
      ttl = config.getTTLUnit().toMillis(config.getTTL());
      alloc = new WpAllocThread(config, this);
    }
    alloc.start();
  }

  WSlot relieve(long timeout, TimeUnit unit) throws InterruptedException {
    Request request = requestTL.get();
    request.setTimeout(timeout, unit);
    request.requestOp = RELIEVE;
    return perform(request, false, true);
  }
  
  public T claim() throws PoolException, InterruptedException {
    Request request = requestTL.get();
    request.setNoTimeout();
    request.requestOp = CLAIM;
    WSlot slot = perform(request, true, true);
    return objectOf(slot);
  }

  public T claim(long timeout, TimeUnit unit) throws PoolException,
      InterruptedException {
    if (unit == null) {
      throw new IllegalArgumentException("timeout TimeUnit cannot be null.");
    }
    Request request = requestTL.get();
    request.setTimeout(timeout, unit);
    request.requestOp = CLAIM;
    WSlot slot = perform(request, true, true);
    return objectOf(slot);
  }

  @SuppressWarnings("unchecked")
  private T objectOf(WSlot slot) {
    if (slot == null) {
      return null;
    }
    if (slot.poison != null) {
      Exception exception = slot.poison;
      slot.created = 0;
      release(slot);
      throw new PoolException("allocation failed", exception);
    }
    if (slot == SHUTDOWN) {
      throw new IllegalStateException("pool is shutdown");
    }
    slot.claimed = true;
    return (T) slot.obj;
  }

  void release(WSlot slot) {
    Request request = requestTL.get();
    request.setTimeout(1, TimeUnit.HOURS);
    request.requestOp = slot;
    try {
      perform(request, false, false);
    } catch (InterruptedException e) {
      // this is not possible, but regardless...
      Thread.currentThread().interrupt();
    }
  }

  private WSlot perform(
      Request request, boolean checkShutdown, boolean interruptible)
  throws InterruptedException {
    for (;;) {
      if (checkShutdown && shutdown) {
        throw new IllegalStateException("pool is shut down");
      }
      if (interruptible && Thread.interrupted()) {
        throw new InterruptedException();
      }
      if (request.active) {
        // step 2
        if (lockCas.compareAndSet(this, UNLOCKED, LOCKED)) { // step 3
          // step 4 - got lock - we are now a combiner
          combiningPass++;
          scanCombineApply();
          if ((combiningPass & CLEANUP_MASK) == CLEANUP_MASK) {
            cleanUp();
          }
          lock = UNLOCKED;
          WSlot slot = request.response;
          if (slot == null) {
            request.await();
            continue;
          }
          if (slot == TIMEOUT) {
            slot = null;
          }
          request.response = null;
          return slot;
        } else {
          // step 2 - did not get lock - spin-wait for response
          for (int i = 0; i < WAIT_SPINS; i++) {
            WSlot slot = request.response;
            if (slot != null) {
              request.response = null;
              return slot == TIMEOUT? null : slot;
            }
          }
          request.await();
          continue;
        }
      } else {
        // step 5 - reactivate request and insert into publist
        activate(request);
      }
    }
  }

  private void scanCombineApply() {
    // traverse publist, combine ops & set "age" on requests
    long now = System.currentTimeMillis();
    Request current = publist;
    boolean shutdown = this.shutdown;
    while (current != null) {
      WSlot op = current.requestOp;
      if (current.deadlineIsPast(now)) {
        replyTo(current, TIMEOUT);
      } else if (op == CLAIM) {
        // a claim request
        // TODO optimize when claim comes before release on a depleted pool
        if (shutdown) {
          replyTo(current, SHUTDOWN);
        } else if (liveStack != null) {
          WSlot prospect = liveStack;
          liveStack = prospect.next;
          if (expired(prospect, now)) {
            prospect.next = deadStack;
            deadStack = prospect;
            continue;
          }
          replyTo(current, prospect);
        }
      } else if (op == RELIEVE) {
        // a relieve (reallocate) request
        WSlot response = deadStack;
        if (response != null) {
          deadStack = response.next;
          replyTo(current, response);
        } else if (shutdown && liveStack != null) {
          response = liveStack;
          liveStack = response.next;
          replyTo(current, response);
        }
      } else if (op != null) {
        // a release request
        if (expired(op, now)) {
          op.next = deadStack;
          deadStack = op;
        } else {
          op.next = liveStack;
          liveStack = op;
        }
        replyTo(current, RELEASE);
      }
      current = current.next;
    }
  }

  private boolean expired(WSlot prospect, long now) {
    return prospect.created + ttl < now;
  }

  private void replyTo(Request request, WSlot response) {
    request.requestOp = null;
    request.response = response;
    request.passCount = combiningPass;
    request.hasTimeout = false;
    request.unpark();
  }

  private void cleanUp() {
    // Called when the combiningPass count say it's time
    Request current = publist;
    // initial 'current' value is never null because publist at this point is
    // guaranteed to contain at least one Request object - namely our own.
    while (current.next != null) {
      if (expired(current.next) && current.requestOp == null) {
        current.next.active = false;
        current.next = current.next.next;
      } else {
        current = current.next;
      }
    }
  }

  private boolean expired(Request request) {
    return combiningPass - request.passCount > EXPIRE_PASS_COUNT;
  }

  private void activate(Request request) {
    request.active = true;
    do {
      request.next = publist;
    } while (!publistCas.compareAndSet(this, request.next, request));
  }

  public Completion shutdown() {
    alloc.shutdown();
    shutdown = true;
    return alloc;
  }
}
