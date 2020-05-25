/*
 * Copyright (C) 2017 Anton Kaliturin <kaliturin@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.kaliturin.blacklist.fragments;


import android.content.Context;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.kaliturin.blacklist.R;
import com.kaliturin.blacklist.activities.CustomFragmentActivity;
import com.kaliturin.blacklist.adapters.SMSConversationCursorAdapter;
import com.kaliturin.blacklist.receivers.InternalEventBroadcast;
import com.kaliturin.blacklist.services.SMSSendService;
import com.kaliturin.blacklist.utils.ContactsAccessHelper;
import com.kaliturin.blacklist.utils.DatabaseAccessHelper;
import com.kaliturin.blacklist.utils.DatabaseAccessHelper.Contact;
import com.kaliturin.blacklist.utils.DefaultSMSAppHelper;
import com.kaliturin.blacklist.utils.DialogBuilder;
import com.kaliturin.blacklist.utils.MessageLengthCounter;
import com.kaliturin.blacklist.utils.Permissions;
import com.kaliturin.blacklist.utils.Utils;


/**
 * Fragment for showing one SMS conversation
 */
public class SMSConversationFragment extends Fragment implements FragmentArguments {
    private static final int END_OF_LIST = -1;
    private InternalEventBroadcast internalEventBroadcast = null;
    private SMSConversationCursorAdapter cursorAdapter = null;
    private ListView listView = null;
    private String contactName = null;
    private String contactNumber = null;
    private EditText messageEdit = null;

    public SMSConversationFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(final @Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
                             final Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_sms_conversation, container, false);
    }

    @Override
    public void onViewCreated(final View view, final @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // notify user if permission isn't granted
        Permissions.notifyIfNotGranted(getContext(), Permissions.READ_SMS);

        // message counter view
        TextView counterTextView = (TextView) view.findViewById(R.id.text_message_counter);
        // send button area
        final View buttonArea = view.findViewById(R.id.button_send_area);
        // message body edit
        messageEdit = (EditText) view.findViewById(R.id.text_message);
        // init message length counting
        messageEdit.addTextChangedListener(new MessageLengthCounter(counterTextView));
        messageEdit.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(final View v, final boolean hasFocus) {
                if (hasFocus) {
                    // increase edit
                    messageEdit.setMinLines(2);
                    // set visible of the button area
                    buttonArea.setVisibility(View.VISIBLE);
                }
            }
        });

        // init "send" button
        ImageButton sendButton = (ImageButton) view.findViewById(R.id.button_send);
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                sendSMSMessage();
            }
        });

        // get fragment's arguments
        Bundle arguments = getArguments();
        int threadId = 0, unreadCount = 0;
        if (arguments != null) {
            contactName = arguments.getString(CONTACT_NAME);
            contactNumber = arguments.getString(CONTACT_NUMBER);
            threadId = arguments.getInt(THREAD_ID);
            unreadCount = arguments.getInt(UNREAD_COUNT);
        }
        if (contactName == null || contactNumber == null) {
            getActivity().finish();
        }

        // init internal broadcast event receiver
        internalEventBroadcast = new InternalEventBroadcast() {
            @Override
            public void onSMSWasWritten(final @NonNull String phoneNumber) {
                if (contactNumber.equals(phoneNumber)) {
                    // reload sms messages in the list
                    loadListViewItems(END_OF_LIST, 1);
                }
            }
        };
        internalEventBroadcast.register(getContext());

        // cursor adapter
        cursorAdapter = new SMSConversationCursorAdapter(getContext());
        cursorAdapter.setOnLongClickListener(new RowOnLongClickListener());

        // add cursor listener to the list
        listView = (ListView) view.findViewById(R.id.rows_list);
        listView.setAdapter(cursorAdapter);

        // load sms messages of the conversation to the list
        loadListViewItems(threadId, unreadCount, END_OF_LIST);
    }

    @Override
    public void onDestroyView() {
        getLoaderManager().destroyLoader(0);
        internalEventBroadcast.unregister(getContext());
        super.onDestroyView();
    }

    @Override
    public void onCreateOptionsMenu(final Menu menu, final MenuInflater inflater) {
        inflater.inflate(R.menu.main, menu);

        MenuItem menuItem = menu.findItem(R.id.write_message);
        Utils.setMenuIconTint(getContext(), menuItem, R.attr.colorAccent);
        menuItem.setVisible(true);
        menuItem.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(final MenuItem item) {
                // open activity with fragment of sending SMS
                openSMSSendActivity(contactName, contactNumber, messageEdit.getText().toString());
                messageEdit.setText("");
                return true;
            }
        });

        super.onCreateOptionsMenu(menu, inflater);
    }

//----------------------------------------------------

    // Opens activity with SMS-sending fragment
    void openSMSSendActivity(final String person, final String number, final String body) {
        // put arguments for the SMS sending fragment
        Bundle arguments = new Bundle();
        arguments.putString(CONTACT_NAME, person);
        arguments.putString(CONTACT_NUMBER, number);
        arguments.putString(SMS_MESSAGE_BODY, body);
        // open activity with the fragment
        CustomFragmentActivity.show(getContext(),
                getString(R.string.New_message),
                SMSSendFragment.class, arguments);
    }

    // Loads SMS conversation to the list view and scrolls to passed position
    private void loadListViewItems(final int listPosition, final int unreadCount) {
        Bundle arguments = getArguments();
        if (arguments != null) {
            int threadId = arguments.getInt(THREAD_ID);
            // load sms messages of the conversation to the list
            loadListViewItems(threadId, unreadCount, listPosition);
        }
    }

    // Loads SMS conversation to the list view
    private void loadListViewItems(final int threadId, final int unreadCount, final int listPosition) {
        if (!isAdded()) {
            return;
        }
        int loaderId = 0;
        ConversationLoaderCallbacks callbacks =
                new ConversationLoaderCallbacks(getContext(),
                        threadId, unreadCount, listView, listPosition, cursorAdapter);

        LoaderManager manager = getLoaderManager();
        if (manager.getLoader(loaderId) == null) {
            // init and run the items loader
            manager.initLoader(loaderId, null, callbacks);
        } else {
            // restart loader
            manager.restartLoader(loaderId, null, callbacks);
        }
    }

//----------------------------------------------------

    // SMS conversation loader
    private static class ConversationLoader extends CursorLoader {
        private int threadId;

        ConversationLoader(final Context context, final int threadId) {
            super(context);
            this.threadId = threadId;
        }

        @Override
        public Cursor loadInBackground() {
            ContactsAccessHelper db = ContactsAccessHelper.getInstance(getContext());
            // get SMS records by thread id
            return db.getSMSMessagesByThreadId2(getContext(), threadId, false, 0);
        }
    }

    // SMS conversation loader callbacks
    private static class ConversationLoaderCallbacks implements LoaderManager.LoaderCallbacks<Cursor> {
        private SMSConversationCursorAdapter cursorAdapter;
        private Context context;
        private int threadId;
        private int unreadCount;
        private ListView listView;
        private int listPosition;

        ConversationLoaderCallbacks(final Context context, final int threadId, final int unreadCount, final ListView listView,
                                    final int listPosition, final SMSConversationCursorAdapter cursorAdapter) {
            this.context = context;
            this.threadId = threadId;
            this.unreadCount = unreadCount;
            this.listView = listView;
            this.listPosition = listPosition;
            this.cursorAdapter = cursorAdapter;
        }

        @Override
        public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
            return new ConversationLoader(context, threadId);
        }

        @Override
        public void onLoadFinished(final Loader<Cursor> loader, final Cursor cursor) {
            // apply loaded data to cursor adapter
            cursorAdapter.changeCursor(cursor);

            // scroll list to the bottom or saved position
            listView.post(new Runnable() {
                @Override
                public void run() {
                    Cursor cursor = cursorAdapter.getCursor();
                    if (cursor != null && !cursor.isClosed()) {
                        int count = cursor.getCount();
                        if (count > 0) {
                            int pos = (listPosition == END_OF_LIST ? count - 1 : listPosition);
                            listView.setSelection(pos);
                            listView.setVisibility(View.VISIBLE);
                        }
                    }
                }
            });

            // are there unread sms in the thread
            if (unreadCount > 0) {
                // mark such sms as are read
                new SMSReadMarker(context).execute(threadId);
            }
        }

        @Override
        public void onLoaderReset(final Loader<Cursor> loader) {
            cursorAdapter.changeCursor(null);
        }
    }

    // Async task - marks SMS of the thread are read
    private static class SMSReadMarker extends AsyncTask<Integer, Void, Void> {
        private Context context;

        SMSReadMarker(final Context context) {
            this.context = context;
        }

        @Override
        protected Void doInBackground(final Integer... params) {
            // mark all messages from thread as read
            int threadId = params[0];
            ContactsAccessHelper db = ContactsAccessHelper.getInstance(context);
            if (db.setSMSMessagesReadByThreadId(context, threadId)) {
                // send broadcast event that SMS thread was read
                InternalEventBroadcast.sendSMSThreadWasRead(context, threadId);
            }
            return null;
        }
    }

    // On row long click listener
    private class RowOnLongClickListener implements View.OnLongClickListener {
        @Override
        public boolean onLongClick(final View view) {
            // view here may be itself as a row as a some child view of a row
            final ContactsAccessHelper.SMSMessage sms = cursorAdapter.getSMSMessage(view);
            if (sms == null) {
                return true;
            }

            // create menu dialog
            DialogBuilder dialog = new DialogBuilder(getContext());
            // add dialog title as message snippet
            dialog.setTitle(sms.body, 1);
            // 'copy text' to clipboard
            dialog.addItem(R.string.Copy_message, new View.OnClickListener() {
                @Override
                public void onClick(final View v) {
                    if (Utils.copyTextToClipboard(getContext(), sms.body)) {
                        Toast.makeText(getContext(), R.string.Copied_to_clipboard,
                                Toast.LENGTH_SHORT).show();
                    }
                }
            });
            // 'delete message'
            dialog.addItem(R.string.Delete_message, new View.OnClickListener() {
                @Override
                public void onClick(final View v) {
                    if (!DefaultSMSAppHelper.isDefault(getContext())) {
                        Toast.makeText(getContext(), R.string.Need_default_SMS_app,
                                Toast.LENGTH_SHORT).show();
                    } else {
                        ContactsAccessHelper db = ContactsAccessHelper.getInstance(getContext());
                        if (db.deleteSMSMessageById(getContext(), sms.id)) {
                            // send broadcast event that SMS was deleted
                            InternalEventBroadcast.sendSMSWasDeleted(getContext(), contactNumber);
                            // reload sms messages in the list
                            int listPosition = listView.getFirstVisiblePosition();
                            loadListViewItems(listPosition, 0);
                        }
                    }
                }
            });
            // 'forward message'
            dialog.addItem(R.string.Forward_message, new View.OnClickListener() {
                @Override
                public void onClick(final View v) {
                    openSMSSendActivity(null, null, sms.body);
                }
            });

            final DatabaseAccessHelper db = DatabaseAccessHelper.getInstance(getContext());
            if (db != null) {
                // 'move contact to black list'
                DatabaseAccessHelper.Contact contact = db.getContact(contactName, contactNumber);
                if (contact == null || contact.type != Contact.TYPE_BLACK_LIST) {
                    dialog.addItem(R.string.Move_to_black_list, new View.OnClickListener() {
                        @Override
                        public void onClick(final View v) {
                            db.addContact(Contact.TYPE_BLACK_LIST, contactName, contactNumber);
                        }
                    });
                }

                // 'move contact to white list'
                if (contact == null || contact.type != Contact.TYPE_WHITE_LIST) {
                    dialog.addItem(R.string.Move_to_white_list, new View.OnClickListener() {
                        @Override
                        public void onClick(final View v) {
                            db.addContact(Contact.TYPE_WHITE_LIST, contactName, contactNumber);
                        }
                    });
                }
            }

            dialog.show();

            return true;
        }
    }

    // Sends SMS message
    boolean sendSMSMessage() {
        if (Permissions.notifyIfNotGranted(getContext(), Permissions.SEND_SMS)
                || Permissions.notifyIfNotGranted(getContext(), Permissions.READ_PHONE_STATE)) {
            return false;
        }

        View view = getView();
        if (view == null) {
            return false;
        }

        if (contactNumber == null) {
            Toast.makeText(getContext(), R.string.Address_is_not_defined, Toast.LENGTH_SHORT).show();
            return false;
        }

        // get SMS message text
        String message = messageEdit.getText().toString();
        if (message.isEmpty()) {
            Toast.makeText(getContext(), R.string.Message_text_is_not_defined, Toast.LENGTH_SHORT).show();
            return false;
        }

        // send SMS message
        SMSSendService.start(getContext(), message, new String[]{contactNumber});

        // clear message edit
        messageEdit.setText("");

        return true;
    }
}
