/**
 * Characterisation tests that pin the Hazelcast 5.7.0 behaviours the sealed cluster (tasks 221 to 229) relies on.
 * Each test fails if a Hazelcast upgrade changes the answer. Nothing here changes production code, and no test
 * uses Data Prism's stores.
 *
 * <h2>Outcome</h2>
 *
 * <p>Pinned Hazelcast: 5.7.0, the only 5.7.x on Maven Central when this was written. "Design" is
 * {@code docs/plan/design/cluster-security.md}.
 *
 * <table>
 * <caption>Question, observed answer, test and the tasks that rely on it</caption>
 * <tr><th>Q</th><th>Observed answer</th><th>Test</th><th>Relied on by</th></tr>
 * <tr><td>1</td><td>A connection from an address outside {@code network/interfaces} is accepted: the joiner,
 * arriving from this host's non-loopback address, joined a member whose {@code interfaces} lists only 127.0.0.1.
 * Confirms the design's "no inbound allowlist". The test is skipped on a host with no non-loopback IPv4
 * address.</td><td>{@code Q01InboundAllowlistSpikeTest.aMemberOutsideTheInterfacesIsAccepted}</td><td>221</td></tr>
 * <tr><td>2</td><td>Every {@code com.hazelcast.system*} log line starts with
 * {@code [address]:port [cluster name] [version] message}, for example
 * {@code [127.0.0.1]:5701 [orders-cluster] [5.7.0] message}. The prefix is part of the message text. A few early
 * lines use {@code [LOCAL]} in place of the address.</td>
 * <td>{@code Q02LogLineSpikeTest.aLogLineCarriesTheAddressThenTheClusterNameThenTheVersionEachInBrackets}</td>
 * <td>221</td></tr>
 * <tr><td>3</td><td>See the administration table below.</td>
 * <td>{@code Q03AdministrationSpikeTest}</td><td>221, 227</td></tr>
 * <tr><td>13</td><td>See the Question 13 table below.</td><td>{@code Q13ManagementCenterProtocolSpikeTest}</td><td>221</td></tr>
 * <tr><td>4</td><td>With {@code defaults-disabled} and only String, Integer and Boolean listed: members join, maps,
 * locks and a split-brain merge work. An entry processor is refused, and the refusal is not an exception at the
 * caller but a timeout: the owner's error reply is a Java-serialised exception that the caller's filter then
 * refuses ("Resolving class HazelcastSerializationException is not allowed" in the log), so the call ends with
 * {@code OperationTimeoutException}. Data Prism's processors (Serializable records) need the prefix
 * {@code io.github.aindriub.dataprism.hazelcast.}; a processor with an {@code Instant} field also needs
 * {@code java.time.Ser}. A keyless client sending a disallowed class gets
 * {@code HazelcastSerializationException} caused by {@code SecurityException: Resolving class ... is not allowed}.
 * The design assumed a {@code SecurityException} on every path; between members it is a timeout. When the caller
 * itself owns the key, the processor applies on the primary, the backup is refused, and the call still returns
 * success after the backup times out (about 6 s); after the primary is killed the promoted backup holds the old
 * value, so primary and backup silently diverge. The split-brain merge ran with only the three classes: each
 * side's unique key survived under PutIfAbsentMergePolicy (a DiscardMergePolicy mutation fails the test).</td>
 * <td>{@code Q04SerializationFilterSpikeTest}</td><td>221</td></tr>
 * <tr><td>5</td><td>Jet: {@code JetConfig.setEnabled(false)}; the default of an untouched {@code Config} is off.
 * User-code deployment: {@code UserCodeDeploymentConfig.setEnabled(false)}; default off. User-code namespaces:
 * cannot be enabled on OSS (start fails with "requires Hazelcast Enterprise Edition"). Phone-home: property
 * {@code hazelcast.phone.home.enabled=false}; unset means ON, and {@code Config.getProperty} and
 * {@code HazelcastProperties} (what the table reads) fall back to the {@code -D} system property, with a config
 * property winning; environment variables were not tested. SQL has no switch of its own: with
 * Jet off a query fails with {@code JetDisabledException}, and in this build the {@code hazelcast-sql} module is not
 * on the classpath so any query fails with {@code HazelcastSqlException}. Read-back works on all three paths with
 * {@code instance.getConfig()}. {@code ClusterMembership.toConfig} reads back Jet off, user code off, phone-home
 * false. A supplied Config keeps Jet and user-code deployment as supplied and has phone-home forced to false by
 * {@code PrivacyCluster.embedded}. A supplied running instance is left entirely as it was, phone-home included:
 * it reads back true, Jet true, user code true, and cannot be switched off after start.</td>
 * <td>{@code Q05SubsystemSwitchesSpikeTest}</td><td>221</td></tr>
 * <tr><td>6</td><td>A member started with the UUID of a live member does not join: each side takes the other for
 * itself ("Packet send task is rejected. Target is this node!") and both stay clusters of one, observed for 15
 * seconds. The impostor never appears in the live member list. The design assumed it might join and be treated
 * as the member until its card lease ended.</td>
 * <td>{@code Q06DuplicateUuidSpikeTest.aMemberWithALiveMembersUuidStartsAloneAndDoesNotJoinWithinFifteenSeconds}</td>
 * <td>227</td></tr>
 * <tr><td>7</td><td>{@code IMap.localKeySet()} is exactly the keys this member owns as primary: across members the
 * sets are disjoint, their union is every key, and backups are excluded. After a member joins and migration
 * settles the sets again partition the keys exactly. Sampled continuously from before a third member joined until
 * {@code isClusterSafe()}, the first two members' sets were never incomplete or overlapping; that is
 * timing-dependent and not asserted, so do not rely on it during migration. {@code IMap} has no
 * partition-local iterator: its only iterators are {@code iterator()} and {@code iterator(fetchSize)}, both over
 * the whole cluster. The design's {@code localKeySet} is available.</td>
 * <td>{@code Q07LocalKeySetSpikeTest}</td><td>229</td></tr>
 * <tr><td>8</td><td>By default {@code process} is re-run on the backup replica: a processor that writes a random
 * value leaves the primary and the backup holding different values. A processor that returns a custom
 * {@code getBackupProcessor()} replaces the re-run: {@code process} ran once, the backup processor ran on the
 * backup, and the backup holds the primary's exact bytes (the processor can keep the computed value in a
 * transient field and return a backup processor carrying it). Backup TTL: when the default re-run sets the TTL
 * through {@code ExtendedMapEntry.setValue(value, ttl, unit)} the backup has the same TTL (600 s read back
 * after the primary was killed); a backup processor that only copies the value leaves the backup with no TTL
 * ({@code Long.MAX_VALUE}), so a custom backup processor must set the TTL itself. After the owner is killed
 * while the processor is running, the invocation is retried and the processor runs again on the new owner
 * (two runs in total; the first never applied anything). Not constructed: an owner killed after applying but
 * before replying, which 232 established can also retry.</td>
 * <td>{@code Q08EntryProcessorBackupSpikeTest}</td><td>223, 224, 226</td></tr>
 * <tr><td>9</td><td>Yes for both, through {@code HazelcastInstanceAware}. An entry processor implementing it gets
 * the executing member's instance (also on the backup, where it saw the backup member's own value, not the
 * primary's), and {@code instance.getUserContext()} returns the member's own {@code Config.getUserContext()}
 * entries, which are not replicated. A {@code SplitBrainMergePolicy} implementing it is injected too: it read its
 * member's context during a merge. So {@code ApprovalMergePolicy} and {@code PendingMergePolicy} can reach a
 * member-local key ring and could unseal values, if they implement {@code HazelcastInstanceAware}.</td>
 * <td>{@code Q09MemberLocalStateSpikeTest}</td><td>223, 224, 226</td></tr>
 * <tr><td>10</td><td>Against a key another member or a client holds locked: {@code tryPut(.., timeout)} and
 * {@code tryRemove(.., timeout)} return false within the timeout. {@code replace(key, old, new)} and
 * {@code remove(key, value)} wait without bound (still waiting after 8 s, past the 5 s call timeout the members were given, completed after the unlock). Same for a
 * member and a client as the holder.</td>
 * <td>{@code Q10LockedKeySpikeTest}</td><td>223, 228</td></tr>
 * <tr><td>11</td><td>On the smaller side after a heal the lifecycle listener gets {@code MERGING}, then the merge
 * policy runs for the smaller side's entries, then {@code MERGED}; the policy has finished by {@code MERGED}. The
 * larger side's members get neither event. The merge policy runs once per merged entry.</td>
 * <td>{@code Q11MergedEventSpikeTest}</td><td>227, 228</td></tr>
 * <tr><td>12</td><td>Not run. Maven Central lists 5.5.0, 5.6.0 and 5.7.0 for {@code com.hazelcast:hazelcast}: no
 * second 5.7.x patch jar exists to start a mixed-version member against. Re-check when a 5.7.x patch is
 * published.</td><td>none</td><td>221</td></tr>
 * </table>
 *
 * <h3>Question 3: administrative operations for a joined member holding only the cluster name</h3>
 *
 * <table>
 * <caption>Allowed or refused, per kind of caller</caption>
 * <tr><th>Operation</th><th>Keyless member</th><th>Client</th></tr>
 * <tr><td>Cluster-state change ({@code getCluster().changeClusterState})</td><td>allowed</td>
 * <td>refused only by the Java client proxy, locally ({@code UnsupportedOperationException}); the member accepts
 * the Management Center protocol message (MCChangeClusterStateCodec) sent through the client's connection, and the
 * cluster froze</td></tr>
 * <tr><td>Cluster shutdown ({@code getCluster().shutdown()})</td><td>allowed (the whole cluster stops)</td>
 * <td>refused only by the Java client proxy, locally; the member accepts MCShutdownClusterCodec from a client and
 * the whole cluster stopped</td></tr>
 * <tr><td>Map destroy ({@code IMap.destroy})</td><td>allowed</td><td>allowed</td></tr>
 * <tr><td>Lock force-unlock ({@code IMap.forceUnlock})</td><td>allowed</td><td>allowed</td></tr>
 * </table>
 *
 * <h3>Question 13: can anything stop a keyless client sending Management Center protocol messages?</h3>
 *
 * <p>Each variant started one member, connected a client that knows only the cluster name, and sent
 * MCChangeClusterStateCodec (FROZEN) through the client's connection:
 *
 * <table>
 * <caption>Variants tested and result</caption>
 * <tr><th>Member configuration</th><th>Result</th></tr>
 * <tr><td>default</td><td>allowed, cluster FROZEN</td></tr>
 * <tr><td>{@code ManagementCenterConfig}: console, scripting, data access all disabled</td>
 * <td>allowed, cluster FROZEN (these switches do not gate this message)</td></tr>
 * <tr><td>{@code trustedInterfaces} = {10.255.255.1} (does not match the client at 127.0.0.1)</td>
 * <td>refused with {@code AccessControlException}, cluster stays ACTIVE; ordinary map use by the same client
 * still works</td></tr>
 * <tr><td>{@code trustedInterfaces} = {127.0.0.1}</td><td>allowed, cluster FROZEN</td></tr>
 * <tr><td>advanced network, member endpoint only, no client endpoint</td><td>no client can connect
 * ({@code IllegalStateException}); {@code PrivacyCluster} refuses advanced network, so this is not usable
 * as is</td></tr>
 * </table>
 *
 * <p>So {@code ManagementCenterConfig.trustedInterfaces} is the OSS switch that stops these messages. It is
 * address-based and so as strong as the network path; it is not tested here whether it covers every MC message
 * type, only change-state. Data Prism does not set it today.
 */
package io.github.aindriub.dataprism.hazelcast.spike;
