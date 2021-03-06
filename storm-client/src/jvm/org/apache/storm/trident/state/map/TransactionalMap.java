/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.  The ASF licenses this file to you under the Apache License, Version
 * 2.0 (the "License"); you may not use this file except in compliance with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package org.apache.storm.trident.state.map;

import java.util.ArrayList;
import java.util.List;
import org.apache.storm.trident.state.TransactionalValue;
import org.apache.storm.trident.state.ValueUpdater;


public class TransactionalMap<T> implements MapState<T> {
    CachedBatchReadsMap<TransactionalValue> backing;
    Long currTx;

    protected TransactionalMap(IBackingMap<TransactionalValue> backing) {
        this.backing = new CachedBatchReadsMap(backing);
    }

    public static <T> MapState<T> build(IBackingMap<TransactionalValue> backing) {
        return new TransactionalMap<T>(backing);
    }

    @Override
    public List<T> multiGet(List<List<Object>> keys) {
        List<CachedBatchReadsMap.RetVal<TransactionalValue>> vals = backing.multiGet(keys);
        List<T> ret = new ArrayList<T>(vals.size());
        for (CachedBatchReadsMap.RetVal<TransactionalValue> retval : vals) {
            TransactionalValue v = retval.val;
            if (v != null) {
                ret.add((T) v.getVal());
            } else {
                ret.add(null);
            }
        }
        return ret;
    }

    @Override
    public List<T> multiUpdate(List<List<Object>> keys, List<ValueUpdater> updaters) {
        List<CachedBatchReadsMap.RetVal<TransactionalValue>> curr = backing.multiGet(keys);
        List<TransactionalValue> newVals = new ArrayList<TransactionalValue>(curr.size());
        List<List<Object>> newKeys = new ArrayList();
        List<T> ret = new ArrayList<T>();
        for (int i = 0; i < curr.size(); i++) {
            CachedBatchReadsMap.RetVal<TransactionalValue> retval = curr.get(i);
            TransactionalValue<T> val = retval.val;
            ValueUpdater<T> updater = updaters.get(i);
            TransactionalValue<T> newVal;
            boolean changed = false;
            if (val == null) {
                newVal = new TransactionalValue<T>(currTx, updater.update(null));
                changed = true;
            } else {
                if (currTx != null && currTx.equals(val.getTxid()) && !retval.cached) {
                    newVal = val;
                } else {
                    newVal = new TransactionalValue<T>(currTx, updater.update(val.getVal()));
                    changed = true;
                }
            }
            ret.add(newVal.getVal());
            if (changed) {
                newVals.add(newVal);
                newKeys.add(keys.get(i));
            }
        }
        if (!newKeys.isEmpty()) {
            backing.multiPut(newKeys, newVals);
        }
        return ret;
    }

    @Override
    public void multiPut(List<List<Object>> keys, List<T> vals) {
        List<TransactionalValue> newVals = new ArrayList<TransactionalValue>(vals.size());
        for (T val : vals) {
            newVals.add(new TransactionalValue<T>(currTx, val));
        }
        backing.multiPut(keys, newVals);
    }

    @Override
    public void beginCommit(Long txid) {
        currTx = txid;
        backing.reset();
    }

    @Override
    public void commit(Long txid) {
        currTx = null;
        backing.reset();
    }
}
