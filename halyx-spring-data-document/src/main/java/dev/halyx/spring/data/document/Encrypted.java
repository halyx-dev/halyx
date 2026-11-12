/*
 * Copyright 2026 Kamran Zafar
 *
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
 */
package dev.halyx.spring.data.document;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A record component the repository encrypts on the client, with the {@code CryptoKey} bean named here (one of
 * {@code halyx.keys}): the value is sealed before it reaches the database and opened on the way back, as
 * {@code TableConfig.Builder.encryptedField} does. Keys, the version and indexed components can't be encrypted, and
 * query methods can't filter or sort on an encrypted component. All encrypted components of one save share one data
 * key, so a save costs one key service call, and so does reading an entity.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD})
public @interface Encrypted {

    /** The {@code CryptoKey} bean name. */
    String value();
}
