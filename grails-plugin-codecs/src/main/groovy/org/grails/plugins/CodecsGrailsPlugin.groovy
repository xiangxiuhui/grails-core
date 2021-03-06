/*
 * Copyright 2004-2005 the original author or authors.
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
package org.grails.plugins

import grails.core.GrailsApplication
import grails.plugins.Plugin
import grails.util.GrailsUtil
import org.grails.plugins.codecs.DefaultCodecLookup
import org.grails.commons.CodecArtefactHandler
import org.grails.plugins.codecs.Base64Codec
import org.grails.encoder.impl.HTML4Codec
import org.grails.plugins.codecs.HTMLCodec
import org.grails.encoder.impl.HTMLJSCodec
import org.grails.plugins.codecs.JSONCodec
import org.grails.plugins.codecs.HexCodec
import org.grails.encoder.impl.JavaScriptCodec
import org.grails.plugins.codecs.MD5BytesCodec
import org.grails.plugins.codecs.MD5Codec
import org.grails.encoder.impl.RawCodec
import org.grails.plugins.codecs.SHA1BytesCodec
import org.grails.plugins.codecs.SHA1Codec
import org.grails.plugins.codecs.SHA256Codec
import org.grails.plugins.codecs.SHA256BytesCodec
import org.grails.plugins.codecs.URLCodec
import org.grails.plugins.codecs.XMLCodec

/**
 * Configures pluggable codecs.
 *
 * @author Jeff Brown
 * @since 0.4
 */
class CodecsGrailsPlugin extends Plugin {
    def version = GrailsUtil.getGrailsVersion()
    def dependsOn = [core:version]
    def watchedResources = "file:./grails-app/utils/**/*Codec.groovy"
    def providedArtefacts = [
        HTMLCodec,
        HTML4Codec,
        XMLCodec,
        JSONCodec,
        JavaScriptCodec,
        HTMLJSCodec,
        URLCodec,
        Base64Codec,
        MD5Codec,
        MD5BytesCodec,
        HexCodec,
        SHA1Codec,
        SHA1BytesCodec,
        SHA256Codec,
        SHA256BytesCodec,
        RawCodec
    ]

    @Override
    void onChange(Map<String, Object> event) {
        def application = grailsApplication
        if (application.isArtefactOfType(CodecArtefactHandler.TYPE, event.source)) {
            application.addArtefact(CodecArtefactHandler.TYPE, event.source)
            applicationContext.getBean('codecLookup', DefaultCodecLookup).reInitialize()
        }
    }

    @Override
    Closure doWithSpring() {{ ->
        codecLookup(DefaultCodecLookup)
    }}
}
