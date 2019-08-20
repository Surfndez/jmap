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

package rs.ltt.jmap.common.util;

import rs.ltt.jmap.annotation.JmapNamespace;
import rs.ltt.jmap.common.method.MethodCall;

public final class Namespace {

    private Namespace() {

    }

    public static String get(Class<? extends MethodCall> clazz) {
        final Package p = clazz.getPackage();
        final JmapNamespace namespace = p.getAnnotation(JmapNamespace.class);
        return namespace == null ? null : namespace.value();
    }

}