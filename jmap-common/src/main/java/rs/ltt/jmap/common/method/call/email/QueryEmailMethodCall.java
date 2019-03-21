/*
 * Copyright 2019 Daniel Gultsch
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package rs.ltt.jmap.common.method.call.email;

import rs.ltt.jmap.annotation.JmapMethod;
import rs.ltt.jmap.common.entity.Email;
import rs.ltt.jmap.common.entity.EmailQuery;
import rs.ltt.jmap.common.entity.Query;
import rs.ltt.jmap.common.entity.filter.Filter;
import rs.ltt.jmap.common.method.call.standard.QueryMethodCall;

@JmapMethod("Email/query")
public class QueryEmailMethodCall extends QueryMethodCall<Email> {

    private Boolean collapseThreads;


    public QueryEmailMethodCall() {
        super();
    }

    public QueryEmailMethodCall(Filter<Email> filter) {
        super(filter);
    }

    public QueryEmailMethodCall(EmailQuery query) {
        super(query);
        collapseThreads = query.collapseThreads;
    }

    public QueryEmailMethodCall(EmailQuery query, Integer limit) {
        super(query, limit);
        collapseThreads = query.collapseThreads;
    }

    public QueryEmailMethodCall(EmailQuery query, String afterEmailId) {
        super(query, afterEmailId);
        collapseThreads = query.collapseThreads;
    }

    public QueryEmailMethodCall(EmailQuery query, String afterEmailId, Integer limit) {
        super(query, afterEmailId, limit);
        collapseThreads = query.collapseThreads;
    }
}