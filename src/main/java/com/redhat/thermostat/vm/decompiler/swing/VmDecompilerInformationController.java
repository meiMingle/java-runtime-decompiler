package com.redhat.thermostat.vm.decompiler.swing;

import com.redhat.thermostat.vm.decompiler.core.AgentRequestAction;
import com.redhat.thermostat.vm.decompiler.core.AgentRequestAction.RequestAction;
import com.redhat.thermostat.vm.decompiler.core.DecompilerRequestReceiver;
import com.redhat.thermostat.vm.decompiler.core.VmDecompilerStatus;
import com.redhat.thermostat.vm.decompiler.data.Config;
import com.redhat.thermostat.vm.decompiler.data.VmInfo;
import com.redhat.thermostat.vm.decompiler.data.VmManager;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.io.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.stream.Collectors;

/**
 * This class provides Action listeners and result processing for the GUI.
 */
public class VmDecompilerInformationController {

    private final MainFrameView mainFrameView;
    private final BytecodeDecompilerView bytecodeDecompilerView;
    private VmManager vmManager;
    private VmInfo vmInfo;

    private boolean listsUpdating = false;

    NewConnectionView newConnectionDialog;
    LoadingDialog loadingDialog;
    NewConnectionController newConnectionController;

    public VmDecompilerInformationController(MainFrameView mainFrameView, VmManager vmManager) {
        this.mainFrameView = mainFrameView;
        this.bytecodeDecompilerView = mainFrameView.getBytecodeDecompilerView();
        this.vmManager = vmManager;

        updateVmLists();

        vmManager.setUpdateVmListsListener(e -> updateVmLists());

        mainFrameView.setCreateNewConnectionDialogListener(e -> createNewConnectionDialog());

        bytecodeDecompilerView.setClassesActionListener(e -> loadClassNames());

        bytecodeDecompilerView.setBytesActionListener(e -> loadClassBytecode(e.getActionCommand()));

        mainFrameView.setVmChanging(this::changeVm);

        mainFrameView.setHaltAgentListener(e -> haltAgent());

    }

    private void createNewConnectionDialog() {
        newConnectionDialog = new NewConnectionView(mainFrameView);
        newConnectionController = new NewConnectionController(newConnectionDialog, vmManager);
        newConnectionDialog.setVisible(true);
    }

    private void updateVmLists() {
        listsUpdating = true;
        ArrayList<VmInfo> localVms = new ArrayList<>();
        ArrayList<VmInfo> remoteVms = new ArrayList<>();
        vmManager.getAllVm().forEach(info -> {
            if (info.getVmPid() > 0) {
                localVms.add(info);
            } else {
                remoteVms.add(info);
            }
        });
        mainFrameView.setLocalVmList(localVms.toArray(new VmInfo[0]));
        mainFrameView.setRemoteVmList(remoteVms.toArray(new VmInfo[0]));
        listsUpdating = false;
    }

    private void changeVm(ActionEvent event) {
        if (!listsUpdating) {
            JList<VmInfo> vmList = (JList<VmInfo>) event.getSource();
            VmInfo selectedVmInfo = vmList.getSelectedValue();
            mainFrameView.switchPanel(selectedVmInfo != null);
            clearOtherList(vmList);
            haltAgent();
            if (selectedVmInfo != null) {
                new Thread(() -> {
                    this.vmInfo = selectedVmInfo;
                    showDialog();
                    loadClassNames();
                    hideDialog();
                }).start();
            }
        }
    }

    /**
     * If selected list is remoteVmList clears localVmList and vice versa.<br>
     * Effectively merging them into one.
     *
     * @param vmList list that doesn't get cleared containing the VM that user wants to attach.
     */
    private void clearOtherList(JList<VmInfo> vmList) {
        listsUpdating = true;
        switch (vmList.getName()) {
            case "remoteVmList":
                mainFrameView.clearLocalListSelection();
                break;
            case "localVmList":
                mainFrameView.clearRemoteListSelection();
                break;
        }
        listsUpdating = false;
    }

    void showDialog() {
        new Thread(() -> {
            loadingDialog = new LoadingDialog();
            loadingDialog.setAbortActionListener(e ->
                    abortAndCleanup());
            loadingDialog.setVisible(true);
        }).start();
    }

    void hideDialog() {
        loadingDialog.setVisible(false);
    }

    void abortAndCleanup() {
        mainFrameView.switchPanel(false);
        mainFrameView.getBytecodeDecompilerView().reloadClassList(new String[0]);
        mainFrameView.getBytecodeDecompilerView().reloadTextField("");
        haltAgent();
        listsUpdating = true;
        updateVmLists();
        mainFrameView.clearLocalListSelection();
        mainFrameView.clearRemoteListSelection();
        listsUpdating = false;
        hideDialog();
    }

    private void loadClassNames() {
        AgentRequestAction request = createRequest("", RequestAction.CLASSES);
        //DecompilerAgentRequestResponseListener listener = 
        String response = submitRequest(request);
        if (response.equals("ok")) {
            VmDecompilerStatus vmStatus = vmManager.getVmDecompilerStatus(vmInfo);
            String[] classes = vmStatus.getLoadedClassNames();
            while (classes.length == 0) {
                vmStatus = vmManager.getVmDecompilerStatus(vmInfo);
                classes = vmStatus.getLoadedClassNames();
            }
            bytecodeDecompilerView.reloadClassList(classes);
        } else {
            System.err.println("Classes couldn't be loaded");
            //bytecodeDecompilerView.handleError(new LocalizedString(listener.getErrorMessage()));
        }
        return;// listener;
    }

    private void loadClassBytecode(String name) {
        AgentRequestAction request = createRequest(name, RequestAction.BYTES);
        //DecompilerAgentRequestResponseListener listener =
        submitRequest(request);
        String decompiledClass = "";
        boolean success = true;//!listener.isError();
        if (success) {
            VmDecompilerStatus vmStatus = vmManager.getVmDecompilerStatus(vmInfo);
            String expectedClass = "";
            while (!expectedClass.equals(name)) {
                vmStatus = vmManager.getVmDecompilerStatus(vmInfo);
                expectedClass = vmStatus.getBytesClassName();

            }
            String bytesInString = vmStatus.getLoadedClassBytes();

            byte[] bytes = Base64.getDecoder().decode(bytesInString);
            try {
                String path = bytesToFile("temporary-byte-file", bytes);
                Process proc = Runtime.getRuntime().exec("java -jar " + Config.getConfig().getDecompilerPath() + " " + path);
                InputStream in = proc.getInputStream();
                decompiledClass = new BufferedReader(new InputStreamReader(in))
                        .lines().collect(Collectors.joining("\n"));
                ;

            } catch (IOException e) {
                //bytecodeDecompilerView.handleError(new LocalizedString(listener.getErrorMessage()));
            }
            bytecodeDecompilerView.reloadTextField(decompiledClass);
        } else {
            //bytecodeDecompilerView.handleError(new LocalizedString(listener.getErrorMessage()));
        }
    }

    private void haltAgent() {
        try {
            AgentRequestAction request = createRequest("", RequestAction.HALT);
            String response = submitRequest(request);
            if (response.equals("ok")) {
                System.out.println("Agent closing socket and exiting");
            }
        } catch (Exception e) {
            System.out.println("Error when sending request to halt agent");
        }
    }

    private AgentRequestAction createRequest(String className, RequestAction action) {
        VmDecompilerStatus status = vmManager.getVmDecompilerStatus(vmInfo);
        int listenPort = AgentRequestAction.NOT_ATTACHED_PORT;
        String hostname = "localhost";
        if (status != null) {
            listenPort = status.getListenPort();
            hostname = status.getHostname();
        }

        AgentRequestAction request;
        if (action == RequestAction.CLASSES) {
            request = AgentRequestAction.create(vmInfo, hostname, listenPort, action);
        } else if (action == RequestAction.BYTES) {
            request = AgentRequestAction.create(vmInfo, hostname, listenPort, action, className);
        } else if (action == RequestAction.HALT) {
            request = AgentRequestAction.create(vmInfo, hostname, listenPort, action);
        } else {
            throw new AssertionError("Unknown action: " + action);
        }
        return request;
    }

    private String submitRequest(AgentRequestAction request) {
        //DecompilerAgentRequestResponseListener listener = new DecompilerAgentRequestResponseListener(latch);
        DecompilerRequestReceiver receiver = new DecompilerRequestReceiver(vmManager);
        String response = receiver.processRequest(request);
        // wait for the request processing

        return response; //listener
    }

    private String bytesToFile(String name, byte[] bytes) throws IOException {
        String path = "/tmp/" + name + ".class";
        FileOutputStream fos = new FileOutputStream(path);
        fos.write(bytes);
        fos.close();
        return path;
    }

}
