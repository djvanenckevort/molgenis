package org.molgenis.ui.jobs;

import java.util.List;

import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.ItemWriteListener;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.google.gson.Gson;

/**
 * Monitors Job Progress and reports on a {@link SimpMessagingTemplate}
 * 
 * @author fkelpin
 *
 * @param <T>
 *            the result item type
 */
public class StepProgressMonitor<T> implements ItemWriteListener<T>, StepExecutionListener
{
	private StepExecution stepExecution;
	@Autowired
	private SimpMessagingTemplate template;
	private String destination;

	@Override
	public void beforeWrite(List<? extends T> items)
	{

	}

	@Override
	public void afterWrite(List<? extends T> items)
	{
		Progress<T> progress = Progress.<T> create(stepExecution, stepExecution.getJobExecution().getJobParameters()
				.getLong("total"), items);
		Gson gson = new Gson();
		template.convertAndSend("/topic/greetings", gson.toJson(progress));
	}

	@Override
	public void onWriteError(Exception exception, List<? extends T> items)
	{

	}

	@Override
	public void beforeStep(StepExecution stepExecution)
	{
		this.stepExecution = stepExecution;
		JobInstance jobInstance = stepExecution.getJobExecution().getJobInstance();
		destination = String.format("topic/jobs/%s/%s/%s", jobInstance.getJobName(), jobInstance.getId(),
				stepExecution.getStepName());

		System.out.println("destination should be " + destination);
		destination = "/topic/greetings";

	}

	@Override
	public ExitStatus afterStep(StepExecution stepExecution)
	{
		return stepExecution.getExitStatus();
	}

}