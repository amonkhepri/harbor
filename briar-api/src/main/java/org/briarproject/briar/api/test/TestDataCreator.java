package org.briarproject.briar.api.test;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface TestDataCreator {

	/**
	 * Create fake test data on the IoExecutor
	 *
	 * @param numContacts Number of contacts to create. Must be >= 1
	 * @param numPrivateMsgs Number of private messages to create for each
	 * contact.
	 * @param avatarPercent Percentage of contacts
	 * that will use a random profile image. Between 0 and 100.
	 * @param numBlogPosts Number of blog posts to create.
	 * @param numForums Number of forums to create.
	 * @param numForumPosts Number of forum posts to create per forum.
	 * @param numPrivateGroups Number of private groups to create.
	 * @param numPrivateGroupMessages Number of messages to create per private group.
	 * @param onComplete Called on the IoExecutor once creation has finished
	 * (whether it succeeded or failed), so a caller that needs to navigate
	 * away only after the data actually exists can wait for this signal
	 * rather than racing the asynchronous creation.
	 */
	void createTestData(int numContacts, int numPrivateMsgs, int avatarPercent,
			int numBlogPosts, int numForums, int numForumPosts,
			int numPrivateGroups, int numPrivateGroupMessages,
			Runnable onComplete);

	@IoExecutor
	Contact addContact(String name, boolean alias, boolean avatar)
			throws DbException;
}
