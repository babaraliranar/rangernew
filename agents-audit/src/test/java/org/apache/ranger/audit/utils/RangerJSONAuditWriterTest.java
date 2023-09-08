/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ranger.audit.utils;

import org.apache.hadoop.fs.FileSystem;
import org.junit.Test;

import java.util.Map;
import java.util.HashMap;
import java.util.Properties;
import java.util.Collections;
import java.io.PrintWriter;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class RangerJSONAuditWriterTest {

    public Properties props;
    public Map<String, String> auditConfigs;

    public void setup(){
        props = new Properties();
        props.setProperty("test.dir", "/tmp");
        auditConfigs = new HashMap<>();
        auditConfigs.put(FileSystem.FS_DEFAULT_NAME_KEY, FileSystem.DEFAULT_FS);
    }

    @Test
    public void checkReUseFlagInStreamErrors() throws Exception {

        RangerJSONAuditWriter jsonAuditWriter = spy(new RangerJSONAuditWriter());
        PrintWriter out = mock(PrintWriter.class);

        setup();
        jsonAuditWriter.init(props, "test", "localfs", auditConfigs);

        assertFalse(jsonAuditWriter.reUseLastLogFile);
        when(jsonAuditWriter.getLogFileStream()).thenReturn(out);
        when(out.checkError()).thenReturn(true);
        assertFalse(jsonAuditWriter.logJSON(Collections.singleton("This event will not be logged!")));
        assertTrue(jsonAuditWriter.reUseLastLogFile);

        // cleanup
        jsonAuditWriter.fileSystem.deleteOnExit(jsonAuditWriter.auditPath);
        jsonAuditWriter.logJSON(Collections.singleton("cleaning up!"));
        jsonAuditWriter.closeWriter();
    }

    @Test
    public void checkAppendtoFileWhenExceptionsOccur() throws Exception {
        RangerJSONAuditWriter jsonAuditWriter = spy(new RangerJSONAuditWriter());

        setup();
        jsonAuditWriter.init(props, "test", "localfs", auditConfigs);

        // append to org.apache.hadoop.fs.LocalFileSystem is not supported which leads to an exception
        jsonAuditWriter.reUseLastLogFile = true;
        jsonAuditWriter.logJSON(Collections.singleton("Append this line!"));

        assertTrue(jsonAuditWriter.reUseLastLogFile);
        assertNull(jsonAuditWriter.ostream);
        assertNull(jsonAuditWriter.logWriter);

        // cleanup
        jsonAuditWriter.reUseLastLogFile = false;
        jsonAuditWriter.logJSON(Collections.singleton("cleaning up!"));
        jsonAuditWriter.fileSystem.deleteOnExit(jsonAuditWriter.auditPath);
        jsonAuditWriter.logJSON(Collections.singleton("cleaning up!"));
        jsonAuditWriter.closeWriter();
    }


    @Test
    public void checkFileRolloverAfterThreshold() throws Exception {
        RangerJSONAuditWriter jsonAuditWriter = spy(new RangerJSONAuditWriter());

        setup();
        props.setProperty("test.file.rollover.enable.periodic.rollover", "true");
        props.setProperty("test.file.rollover.periodic.rollover.check.sec", "2");
        // rollover log file after this interval
        jsonAuditWriter.fileRolloverSec = 5; // in seconds
        jsonAuditWriter.init(props, "test", "localfs", auditConfigs);


        assertTrue(jsonAuditWriter.logJSON(Collections.singleton("First file created and added this line!")));
        jsonAuditWriter.fileSystem.deleteOnExit(jsonAuditWriter.auditPath); // cleanup
        Thread.sleep(6000);

        assertFalse(jsonAuditWriter.reUseLastLogFile);
        assertNull(jsonAuditWriter.ostream);
        assertNull(jsonAuditWriter.logWriter);

        assertTrue(jsonAuditWriter.logJSON(Collections.singleton("Second file created since rollover happened!")));
        jsonAuditWriter.fileSystem.deleteOnExit(jsonAuditWriter.auditPath); // cleanup
        jsonAuditWriter.closeWriter();
    }
}