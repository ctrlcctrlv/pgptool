/*******************************************************************************
 * PGPTool is a desktop application for pgp encryption/decryption
 * Copyright (C) 2019 Sergey Karpushin
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 ******************************************************************************/
package org.pgptool.gui.ui.keyslist;

import static org.pgptool.gui.app.Messages.text;

import java.awt.Desktop;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.ArrayList;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;

import org.apache.commons.io.FilenameUtils;
import org.apache.log4j.Logger;
import org.pgptool.gui.app.EntryPoint;
import org.pgptool.gui.configpairs.api.ConfigPairs;
import org.pgptool.gui.encryption.api.KeyFilesOperations;
import org.pgptool.gui.encryption.api.dto.Key;
import org.pgptool.gui.hintsforusage.hints.PrivateKeyBackupHint.PrivateKeyExportedEvent;
import org.pgptool.gui.ui.tools.UiUtils;
import org.pgptool.gui.ui.tools.browsefs.FolderChooserDialog;
import org.pgptool.gui.ui.tools.browsefs.SaveFileChooserDialog;
import org.pgptool.gui.ui.tools.browsefs.ValueAdapterPersistentPropertyImpl;
import org.pgptool.gui.usage.api.UsageLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.summerb.easycrud.api.dto.EntityChangedEvent;

import com.google.common.base.Preconditions;
import com.google.common.eventbus.EventBus;

public class KeysExporterUiImpl implements KeysExporterUi {
	private static Logger log = Logger.getLogger(KeysExporterUiImpl.class);

	@Autowired
	private KeyFilesOperations keyFilesOperations;
	@Autowired
	private EventBus eventBus;
	@Autowired
	private ConfigPairs appProps;
	@Autowired
	private UsageLogger usageLogger;

	private FolderChooserDialog folderChooserDialog;

	@Override
	public void exportPublicKey(Key key, ActionEvent originEvent) {
		String targetFile = buildPublicKeyTargetChooser(key).askUserForFile(originEvent);
		if (targetFile == null) {
			return;
		}

		try {
			usageLogger.write(new PublicKeyExportedUsage(key.getKeyInfo().getKeyId(), targetFile));
			keyFilesOperations.exportPublicKey(key, targetFile);
		} catch (Throwable t) {
			EntryPoint.reportExceptionToUser(originEvent, "error.failedToExportPublicKey", t, key.toString());
			return;
		}

		browseForFolder(FilenameUtils.getFullPath(targetFile));
	}

	public SaveFileChooserDialog buildPublicKeyTargetChooser(Key key) {
		return new SaveFileChooserDialog("action.exportPublicKey", "action.export", appProps, "ExportKeyDialog") {
			@Override
			protected void onFileChooserPostConstruct(JFileChooser ofd) {
				ofd.setAcceptAllFileFilterUsed(false);
				ofd.addChoosableFileFilter(new FileNameExtensionFilter("Key file armored (.asc)", "asc"));
				ofd.addChoosableFileFilter(new FileNameExtensionFilter("Key file (.bpg)", "bpg"));
				ofd.addChoosableFileFilter(ofd.getAcceptAllFileFilter());
				ofd.setFileFilter(ofd.getChoosableFileFilters()[0]);
			}

			@Override
			protected void suggestTarget(JFileChooser ofd) {
				super.suggestTarget(ofd);
				File suggestedFileName = suggestFileNameForKey(key, ofd.getCurrentDirectory().getAbsolutePath(), null,
						false, false);
				ofd.setSelectedFile(suggestedFileName);
			}
		};
	}

	@Override
	public void exportPrivateKey(Key key, ActionEvent originEvent) {
		String targetFile = buildPrivateKeyTargetChooser(key).askUserForFile(originEvent);
		if (targetFile == null) {
			return;
		}

		try {
			usageLogger.write(new PrivateKeyExportedUsage(key.getKeyInfo().getKeyId(), targetFile));
			keyFilesOperations.exportPrivateKey(key, targetFile);
			eventBus.post(EntityChangedEvent.added(new PrivateKeyExportedEvent(key)));
		} catch (Throwable t) {
			EntryPoint.reportExceptionToUser(originEvent, "error.failedToExportPrivateKey", t, key.toString());
			return;
		}

		UiUtils.messageBox(originEvent, text("keys.privateKey.exportWarning"), text("term.attention"),
				JOptionPane.WARNING_MESSAGE);
		browseForFolder(FilenameUtils.getFullPath(targetFile));
	}

	public SaveFileChooserDialog buildPrivateKeyTargetChooser(final Key key) {
		return new SaveFileChooserDialog("action.exportPrivateKey", "action.export", appProps, "ExportKeyDialog") {
			@Override
			protected void onFileChooserPostConstruct(JFileChooser ofd) {
				ofd.setAcceptAllFileFilterUsed(false);
				ofd.addChoosableFileFilter(new FileNameExtensionFilter("Key file armored (.asc)", "asc"));
				ofd.addChoosableFileFilter(new FileNameExtensionFilter("Key file (.bpg)", "bpg"));
				ofd.addChoosableFileFilter(ofd.getAcceptAllFileFilter());
				ofd.setFileFilter(ofd.getChoosableFileFilters()[0]);
			}

			@Override
			protected void suggestTarget(JFileChooser ofd) {
				super.suggestTarget(ofd);

				File suggestedFileName = suggestFileNameForKey(key, ofd.getCurrentDirectory().getAbsolutePath(),
						" - private", false, false);
				ofd.setSelectedFile(suggestedFileName);
			}
		};
	}

	private File suggestFileNameForKey(Key key, String basePathNoSlash, String optionalSuffix, boolean isAddExtension,
			boolean isMitigateOverwrite) {
		String userName = key.getKeyInfo().buildUserNameOnly();
		String fileName = basePathNoSlash + File.separator + userName;
		String fileNameWithoutExt = fileName;
		if (optionalSuffix != null) {
			fileName += optionalSuffix;
		}
		if (isAddExtension) {
			fileName += ".asc";
		}
		if (isMitigateOverwrite) {
			fileName = addKeyIdIfFileAlreadyExists(key, isAddExtension, fileName, fileNameWithoutExt);
		}
		return new File(fileName);
	}

	private String addKeyIdIfFileAlreadyExists(Key key, boolean isAddExtension, String fileName,
			String fileNameWithoutExt) {
		if (!new File(fileName).exists()) {
			return fileName;
		}

		fileName = fileNameWithoutExt + "-" + key.getKeyInfo().getKeyId();
		if (isAddExtension) {
			fileName += ".asc";
		}
		return fileName;
	}

	@Override
	public void exportPublicKeys(ArrayList<Key> keys, ActionEvent originEvent) {
		String newFolder = getFolderChooserDialog().askUserForFolder(originEvent);
		if (newFolder == null) {
			return;
		}

		int keysExported = 0;
		int keysTotal = keys.size();
		try {
			File folder = new File(newFolder);
			Preconditions.checkArgument(folder.exists() || folder.mkdirs(),
					"Failed to verify target folder existance " + newFolder);
			for (int i = 0; i < keys.size(); i++) {
				Key key = keys.get(i);
				File targetFile = suggestFileNameForKey(key, newFolder, null, true, true);
				usageLogger
						.write(new PublicKeyExportedUsage(key.getKeyInfo().getKeyId(), targetFile.getAbsolutePath()));
				keyFilesOperations.exportPublicKey(key, targetFile.getAbsolutePath());
				keysExported++;
			}
		} catch (Throwable t) {
			EntryPoint.reportExceptionToUser(originEvent, "error.failedToExportKeys", t, keysExported, keysTotal);
		} finally {
			if (keysExported > 0) {
				browseForFolder(newFolder);
			}
		}
	}

	public FolderChooserDialog getFolderChooserDialog() {
		if (folderChooserDialog == null) {
			ValueAdapterPersistentPropertyImpl<String> exportedKeysLocation = new ValueAdapterPersistentPropertyImpl<String>(
					appProps, "KeysListPm.exportedKeysLocation", null);
			folderChooserDialog = new FolderChooserDialog(text("keys.chooseFolderForKeysExport"), exportedKeysLocation);
		}
		return folderChooserDialog;
	}

	private void browseForFolder(String targetFileName) {
		try {
			Desktop.getDesktop().browse(new File(targetFileName).toURI());
		} catch (Throwable t) {
			log.warn("Failed to open folder for exported key", t);
		}
	}

}
